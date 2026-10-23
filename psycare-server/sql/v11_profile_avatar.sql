-- =============================================================
-- V11  个人资料：sys_user 增加头像列
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- 【为什么头像放在 sys_user，而不是三张角色表】
--   头像对三种角色语义完全一致（都是「这个人长什么样」），属于
--   v2 拆分时定义的「账号共性」范畴，与 username / password / real_name
--   同一层级。放角色表意味着：
--     · 同一个属性要写三份，将来加「上传头像」功能要改三处；
--     · 查询头像必须按 role 分支查三张表 —— 而侧栏头像在**每一端**都要渲染，
--       等于把一次分支判断塞进最高频的渲染路径里。
--
-- 【为什么是 URL 而不是 BLOB】
--   与 banner 同一套理由（见 UploadService 类注释）：文件落本地磁盘，
--   库里只存相对路径。/api/uploads/avatar/2026/10/19/xxx.png。
--   存 BLOB 会让 sys_user 表体积随用户数线性膨胀，连带拖慢登录查询
--   （登录是唯一对 sys_user 的**高频**点查）。
--
-- 【为什么 VARCHAR(255)】
--   实测路径形态：/api/uploads/avatar/2026/10/19/（32 位 UUID）.png
--   = 12 + 7 + 11 + 32 + 4 ≈ 66 字符。255 留了约 4 倍余量，
--   将来换对象存储换成完整 https URL（含签名查询串）也装得下。
--   再长就该考虑单独开一张 user_asset 表，而不是继续放宽这一列。
--
-- 【为什么允许 NULL 而不是 DEFAULT ''】
--   「没设头像」与「头像就是空字符串」是两回事。用 NULL 表达前者，
--   前端只需 `if (avatarUrl)` 一个判断；DEFAULT '' 会让所有地方
--   都得判空串，且空串会被 `StringUtils.hasText` 之外的地方误当真值。
--
-- 约定沿用：本库无外键约束；逻辑删除统一 is_deleted。
--
-- 执行前置：本脚本**幂等**。MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--   故用 information_schema 判断后再动态执行，重复跑是空操作。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'psycare'
    AND TABLE_NAME   = 'sys_user'
    AND COLUMN_NAME  = 'avatar_url'
);

SET @ddl := IF(@has_col = 0,
  'ALTER TABLE sys_user ADD COLUMN avatar_url VARCHAR(255) NULL COMMENT ''头像相对 URL（/api/uploads/avatar/...），NULL = 未设置'' AFTER phone',
  'SELECT ''avatar_url 已存在，跳过'' AS skipped'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证：应看到 avatar_url，类型 varchar(255)，允许 NULL
SELECT column_name, column_type, is_nullable
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'psycare' AND TABLE_NAME = 'sys_user' AND column_name = 'avatar_url';
