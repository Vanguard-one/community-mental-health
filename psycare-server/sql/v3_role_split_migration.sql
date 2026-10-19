-- =============================================================
-- V3  角色数据拆分（历史数据迁移）
-- 前置：已执行 v2_role_split_ddl.sql，且三张角色表为空
-- 特性：可重复执行（INSERT IGNORE 按主键幂等）、全程单事务、含双向校验与回滚
--
-- 【核心原则：主键沿用原 sys_user.id，不重新编号】
--   理由：sys_user.id 本身全局唯一，按角色切分后三个 id 子集互不重叠
--         → 新表主键天然无冲突；
--         且 assessment.resident_id / alert_record.resident_id /
--         consult_session.resident_id / recommendation_log.resident_id /
--         notification.user_id / alert_record.handler_id /
--         consult_session.doctor_id 这 7 处引用一行都不用改。
--   改成新表自增则要重映射全部 7 处，风险高一个数量级。
--
-- 【本脚本只搬「角色差异字段」】
--   username / password / role / status / is_deleted 留在 sys_user，不复制。
--   因此不存在「改密码要改两张表」这类双写问题。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 0) 迁移前体检：各角色条数、档案表覆盖情况
--    执行迁移前先看这组数字，迁移后用同样的数字核对
-- -------------------------------------------------------------
SELECT '迁移前-源表' AS stage,
       (SELECT COUNT(*) FROM sys_user WHERE role = 'RESIDENT') AS resident,
       (SELECT COUNT(*) FROM sys_user WHERE role = 'DOCTOR')   AS doctor,
       (SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN')    AS admin,
       (SELECT COUNT(*) FROM resident_profile)                 AS resident_profile,
       (SELECT COUNT(*) FROM doctor_profile)                   AS doctor_profile;

-- 孤儿档案检查（有档案无用户 / 档案指向非对应角色），应为 0 行
SELECT '孤儿-resident_profile' AS issue, rp.id, rp.user_id
FROM resident_profile rp
LEFT JOIN sys_user u ON u.id = rp.user_id AND u.role = 'RESIDENT'
WHERE u.id IS NULL;

SELECT '孤儿-doctor_profile' AS issue, dp.id, dp.user_id
FROM doctor_profile dp
LEFT JOIN sys_user u ON u.id = dp.user_id AND u.role = 'DOCTOR'
WHERE u.id IS NULL;

-- -------------------------------------------------------------
-- 1) 正式迁移（单事务）
-- -------------------------------------------------------------
START TRANSACTION;

-- 1.1 居民：sys_user(RESIDENT) LEFT JOIN resident_profile，取差异字段写入
INSERT IGNORE INTO `user`
  (id, gender, birth_year, occupation, emergency_contact, created_at, updated_at)
SELECT u.id, rp.gender, rp.birth_year, rp.occupation, rp.emergency_contact,
       IFNULL(rp.created_at, u.created_at), u.updated_at
FROM sys_user u
LEFT JOIN resident_profile rp ON rp.user_id = u.id
WHERE u.role = 'RESIDENT';

-- 1.2 医生：sys_user(DOCTOR) LEFT JOIN doctor_profile
--     存量医生一律置 audit_status='APPROVED'。
--     若沿用默认值 PENDING，迁移完成后三位医生账号会立刻无法登录
--     （登录链路对未审核医生直接拒绝）。
INSERT IGNORE INTO `doctor`
  (id, license_no, department, title, expertise_tags, rating, current_load,
   available, audit_status, audit_remark, audited_by, audited_at,
   created_at, updated_at)
SELECT u.id, NULL, dp.department, dp.title, dp.expertise_tags,
       IFNULL(dp.rating, 5.0), IFNULL(dp.current_load, 0), IFNULL(dp.available, 1),
       'APPROVED', '存量数据迁移，视为已审核', NULL, u.created_at,
       IFNULL(dp.created_at, u.created_at), u.updated_at
FROM sys_user u
LEFT JOIN doctor_profile dp ON dp.user_id = u.id
WHERE u.role = 'DOCTOR';

-- 1.3 管理员：无档案表，只有分级
--     首个管理员（id 最小）标记为 SUPER，
--     避免治理页出现「普通管理员把最后一个超管删掉」导致无人可管
INSERT IGNORE INTO `admin` (id, admin_level, created_at, updated_at)
SELECT u.id,
       CASE WHEN u.id = (SELECT MIN(id) FROM (SELECT id FROM sys_user WHERE role = 'ADMIN') t)
            THEN 'SUPER' ELSE 'NORMAL' END,
       u.created_at, u.updated_at
FROM sys_user u
WHERE u.role = 'ADMIN';

COMMIT;

-- -------------------------------------------------------------
-- 2) 迁移后校验
--    2.1 条数必须逐一相等
-- -------------------------------------------------------------
SELECT '校验-条数' AS stage,
       (SELECT COUNT(*) FROM sys_user WHERE role = 'RESIDENT') AS src_resident,
       (SELECT COUNT(*) FROM `user`)                           AS dst_resident,
       (SELECT COUNT(*) FROM sys_user WHERE role = 'DOCTOR')   AS src_doctor,
       (SELECT COUNT(*) FROM `doctor`)                         AS dst_doctor,
       (SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN')    AS src_admin,
       (SELECT COUNT(*) FROM `admin`)                          AS dst_admin;

-- 2.2 双向差集：两边都必须为 0 行（只比条数不够，要防串行）
SELECT '缺失-源有目标无' AS issue, u.id, u.username, u.role
FROM sys_user u
LEFT JOIN (
  SELECT id FROM `user`   UNION ALL
  SELECT id FROM `doctor` UNION ALL
  SELECT id FROM `admin`
) t ON t.id = u.id
WHERE t.id IS NULL;

SELECT '多出-目标有源无' AS issue, t.id
FROM (
  SELECT id FROM `user`   UNION ALL
  SELECT id FROM `doctor` UNION ALL
  SELECT id FROM `admin`
) t
LEFT JOIN sys_user u ON u.id = t.id
WHERE u.id IS NULL;

-- 2.3 关键引用完整性：工单 / 测评是否都能找到对应居民
--     ⚠️ 若 consult_session / recommendation_log 查出「断链」，先别怀疑迁移脚本 ——
--        本机真实库已存在脏数据：这两张表里有 resident_id = 1 的记录，
--        而 id=1 实际是 admin 账号（不是居民）。属于历史测试数据，迁移不产生也不修复它。
--        排查：SELECT c.id, c.resident_id, u.username, u.role FROM consult_session c
--              JOIN sys_user u ON u.id = c.resident_id WHERE u.role <> 'RESIDENT';
--        「迁移是否引入新断链」的严格结论由演练脚本的 before/after 对比给出。
SELECT '断链-alert_record' AS issue, a.id, a.resident_id
FROM alert_record a
LEFT JOIN `user` u ON u.id = a.resident_id
WHERE u.id IS NULL;

SELECT '断链-assessment' AS issue, a.id, a.resident_id
FROM assessment a
LEFT JOIN `user` u ON u.id = a.resident_id
WHERE u.id IS NULL;

SELECT '引用非居民账号-consult_session' AS issue, c.id, c.resident_id, u.username, u.role
FROM consult_session c
JOIN sys_user u ON u.id = c.resident_id
LEFT JOIN `user` r ON r.id = c.resident_id
WHERE r.id IS NULL;

-- 2.4 业务字段抽检（对照备份表逐字段比对，应为 0 行差异）
SELECT '不一致-医生档案' AS issue, b.id, b.department, d.department AS new_department
FROM doctor_profile_bak_20260928 b
JOIN doctor d ON d.id = b.user_id
WHERE NOT (b.department <=> d.department)
   OR NOT (b.title <=> d.title)
   OR NOT (b.expertise_tags <=> d.expertise_tags)
   OR NOT (b.rating <=> d.rating);

SELECT '不一致-居民档案' AS issue, b.id, b.gender, u.gender AS new_gender
FROM resident_profile_bak_20260928 b
JOIN `user` u ON u.id = b.user_id
WHERE NOT (b.gender <=> u.gender)
   OR NOT (b.birth_year <=> u.birth_year)
   OR NOT (b.occupation <=> u.occupation)
   OR NOT (b.emergency_contact <=> u.emergency_contact);

-- 2.5 软删列已就位，且存量数据全部为「未删除」
SELECT '软删列状态' AS stage,
       (SELECT COUNT(*) FROM sys_user)                      AS total,
       (SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0) AS alive,
       (SELECT COUNT(*) FROM sys_user WHERE is_deleted = 1) AS deleted;

-- 2.6 存量医生无未审核记录（应为 0 行）
SELECT '未审核-存量医生' AS issue, id FROM doctor WHERE audit_status <> 'APPROVED';

-- -------------------------------------------------------------
-- 3) 回滚方案
-- -------------------------------------------------------------
-- 场景 A：迁移数据有误，想重来一遍
--   TRUNCATE TABLE `user`, `doctor`, `admin`;   -- 清掉重跑本脚本即可（数据源未动）
--
-- 场景 B：放弃拆分，回到单表
--   DROP TABLE `user`, `doctor`, `admin`;
--   ALTER TABLE sys_user DROP INDEX uk_alive_username, ADD UNIQUE KEY username (username);
--   -- is_deleted / deleted_at 两列无害，可保留
--
-- 场景 C：sys_user 被误改，整表还原
--   TRUNCATE TABLE sys_user;
--   INSERT INTO sys_user SELECT * FROM sys_user_bak_20260928;
--   （注意：备份表里 is_deleted / deleted_at 不存在，还原后需重新执行 V2 的第 1 节）
