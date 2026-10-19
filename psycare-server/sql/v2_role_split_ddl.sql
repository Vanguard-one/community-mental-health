-- =============================================================
-- V2  角色数据拆分（DDL）
-- 目标库：MySQL 8.0.13+（用到函数索引），库名 psycare
-- 方案：认证基表 sys_user + 三张角色差异表 user / doctor / admin
--
-- 【设计原则：不冗余账号身份字段】
--   username / password / role / status / is_deleted 只存在于 sys_user，
--   三张角色表**不再重复存一份**。理由：
--     1) 改密码只改一处，不存在双写不一致；
--     2) username 全局唯一约束由基表一处保证，天然成立；
--     3) 禁用/删除只需标记基表，不需要跨表事务。
--   角色表因此是「纯角色差异字段的 1:1 扩展表」。
--
-- 【为什么不需要全局取号器】
--   sys_user 的 AUTO_INCREMENT 继续提供全局唯一 id 空间
--   （notification.user_id / alert_record.handler_id / consult_session.doctor_id
--    这些不带角色的引用依赖它）。角色表主键直接取 sys_user.id，不再自增。
--   只有当 sys_user 被彻底删除（纯三表方案）时才需要 id_sequence —— 本方案不需要。
--
-- 执行前置：
--   1) mysqldump 全库备份
--   2) 停后端 18989（避免迁移窗口内的写入落到旧结构）
--   3) 本脚本「不可重复执行」—— MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--      重复执行报 1060/1061 属预期。用 Flyway/Liquibase 版本号管控。
--
-- 本脚本只做「加」，不做「删」—— sys_user 的旧列一律保留，
-- 待代码全部切换完成后由 V4 清理，保证期间可随时回滚。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 0) 迁移前快照（保留 7 天，人工确认无误后清理）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user_bak_20260928 AS SELECT * FROM sys_user;
CREATE TABLE IF NOT EXISTS resident_profile_bak_20260928 AS SELECT * FROM resident_profile;
CREATE TABLE IF NOT EXISTS doctor_profile_bak_20260928 AS SELECT * FROM doctor_profile;

-- -------------------------------------------------------------
-- 1) 认证基表：补软删列
--    现状问题：application.yml 已配 logic-delete-field，
--    但 sys_user 及全部 15 张表都没有对应列 → 所谓「逻辑删除」实为物理删除。
-- -------------------------------------------------------------
ALTER TABLE sys_user
  ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0未删除 1已删除（逻辑删除）' AFTER status,
  ADD COLUMN deleted_at DATETIME NULL COMMENT '删除时间（审计留痕）' AFTER updated_at;

-- 唯一索引改造：原 UNIQUE(username) 会让「软删后同名无法再注册」。
-- MySQL 8.0.13+ 函数索引：仅对未删除行生效
-- （已删除行的表达式值为 NULL，而唯一索引不约束 NULL，天然放行）。
ALTER TABLE sys_user
  DROP INDEX username,
  ADD UNIQUE KEY uk_alive_username ((IF(is_deleted = 0, username, NULL))) COMMENT '仅未删除行用户名唯一';

-- -------------------------------------------------------------
-- 2) 居民用户表（合并原 resident_profile 的差异字段）
--    主键沿用原 sys_user.id —— 保证 assessment.resident_id /
--    alert_record.resident_id / consult_session.resident_id 等既有引用零改动
-- -------------------------------------------------------------
CREATE TABLE `user` (
  id                BIGINT   NOT NULL COMMENT '主键 = sys_user.id',
  gender            VARCHAR(8)  DEFAULT NULL COMMENT 'M/F/U',
  birth_year        INT         DEFAULT NULL COMMENT '出生年份（弱化识别）',
  occupation        VARCHAR(64) DEFAULT NULL,
  emergency_contact VARCHAR(64) DEFAULT NULL COMMENT '紧急联系人（脱敏展示）',
  created_at        DATETIME    DEFAULT NULL COMMENT '档案创建时间（迁移时 = 账号创建时间）',
  updated_at        DATETIME    DEFAULT NULL COMMENT '档案最后修改时间（语义不同于账号更新时间）',
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '居民用户表（角色差异字段）';

-- -------------------------------------------------------------
-- 3) 医生表（合并原 doctor_profile，并新增资质审核字段）
--    audit_status 是医生注册后能否登录的开关
-- -------------------------------------------------------------
CREATE TABLE `doctor` (
  id             BIGINT       NOT NULL COMMENT '主键 = sys_user.id',
  license_no     VARCHAR(64)  DEFAULT NULL COMMENT '执业证书编号（资质材料）',
  department     VARCHAR(32)  DEFAULT NULL COMMENT '科室',
  title          VARCHAR(32)  DEFAULT NULL COMMENT '职称',
  expertise_tags VARCHAR(255) DEFAULT NULL COMMENT '专长标签，逗号分隔',
  rating         DOUBLE       DEFAULT 5.0,
  current_load   INT          DEFAULT 0 COMMENT '当前接诊量',
  available      TINYINT      DEFAULT 1 COMMENT '1可接诊',
  audit_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审核/APPROVED通过/REJECTED驳回',
  audit_remark   VARCHAR(255) DEFAULT NULL COMMENT '审核意见',
  audited_by     BIGINT       DEFAULT NULL COMMENT '审核人 admin.id',
  audited_at     DATETIME     DEFAULT NULL,
  created_at     DATETIME     DEFAULT NULL,
  updated_at     DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_status (audit_status),
  KEY idx_dept (department),
  KEY idx_available (available, current_load)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '医生表（角色差异字段）';

-- -------------------------------------------------------------
-- 4) 管理员表（无业务档案，只有分级）
-- -------------------------------------------------------------
CREATE TABLE `admin` (
  id          BIGINT      NOT NULL COMMENT '主键 = sys_user.id',
  admin_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'SUPER超级管理员/NORMAL普通管理员',
  created_at  DATETIME    DEFAULT NULL,
  updated_at  DATETIME    DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理员表（角色差异字段）';

-- =============================================================
-- V4（后续，代码全部切换并验证通过后再执行，此处仅备注）
-- =============================================================
-- DROP TABLE resident_profile;   -- 字段已并入 `user`
-- DROP TABLE doctor_profile;     -- 字段已并入 `doctor`
-- -- 注意：sys_user 的 username/password/real_name/phone/role/status 一律【不删】，
-- --       它们是基表的固有职责，不是遗留冗余。
