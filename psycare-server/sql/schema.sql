-- =============================================================
-- 社区心理健康评测与问诊系统 数据库脚本 (MySQL 8)
-- 用法：先执行本脚本建库建表与量表种子数据；
--       用户/医生等需要密码哈希的数据由应用启动时 DataInitializer 初始化。
-- =============================================================
CREATE DATABASE IF NOT EXISTS psycare DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE psycare;

-- ---------- 账号与角色 ----------
-- sys_user 是「认证基表」，唯一认证来源：
-- username / password / role / status / is_deleted 只在这里存在，
-- 三张角色表以 id 与其 1:1，只承载角色差异字段（避免任何双写）。
-- 全局 id 空间由本表 AUTO_INCREMENT 保证 —— notification.user_id、
-- alert_record.handler_id 等不带角色的跨表引用依赖它。
CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(64)  NOT NULL COMMENT '登录名',
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
  real_name   VARCHAR(64)  COMMENT '姓名（展示脱敏）',
  phone       VARCHAR(32)  COMMENT '手机号（存储加密）',
  role        VARCHAR(16)  NOT NULL COMMENT 'RESIDENT/DOCTOR/ADMIN',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删除 1已删除（逻辑删除）',
  created_at  DATETIME,
  updated_at  DATETIME,
  deleted_at  DATETIME     COMMENT '删除时间（审计留痕）',
  -- 唯一索引只约束「未删除行」：已删除行的表达式值为 NULL，
  -- 而唯一索引不约束 NULL，因此软删后同名可以重新注册。
  -- 若写成 UNIQUE(username)，软删用户的用户名会被永久占用。
  UNIQUE KEY uk_alive_username ((IF(is_deleted = 0, username, NULL))) COMMENT '仅未删除行用户名唯一'
) COMMENT '系统账号（认证基表）';

-- 居民用户表（角色差异字段，1:1 挂 sys_user）
CREATE TABLE IF NOT EXISTS `user` (
  id                BIGINT   NOT NULL COMMENT '主键 = sys_user.id',
  gender            VARCHAR(8)  COMMENT 'M/F/U',
  birth_year        INT COMMENT '出生年份（弱化识别）',
  occupation        VARCHAR(64),
  emergency_contact VARCHAR(64) COMMENT '紧急联系人（脱敏展示）',
  created_at        DATETIME,
  updated_at        DATETIME,
  PRIMARY KEY (id)
) COMMENT '居民用户表（角色差异字段）';

-- 医生表（角色差异字段 + 资质审核）
-- audit_status = PENDING 时登录链路直接拒绝，无资质账号不得进入工单处置台
CREATE TABLE IF NOT EXISTS doctor (
  id             BIGINT       NOT NULL COMMENT '主键 = sys_user.id',
  license_no     VARCHAR(64)  COMMENT '执业证书编号',
  department     VARCHAR(32)  COMMENT '科室',
  title          VARCHAR(32)  COMMENT '职称',
  expertise_tags VARCHAR(255) COMMENT '专长标签，逗号分隔',
  rating         DOUBLE DEFAULT 5.0,
  current_load   INT DEFAULT 0,
  available      TINYINT DEFAULT 1 COMMENT '1可接诊',
  audit_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  audit_remark   VARCHAR(255) COMMENT '审核意见',
  audited_by     BIGINT COMMENT '审核人 admin.id',
  audited_at     DATETIME,
  created_at     DATETIME,
  updated_at     DATETIME,
  PRIMARY KEY (id),
  KEY idx_audit_status (audit_status),
  KEY idx_dept (department),
  KEY idx_available (available, current_load)
) COMMENT '医生表（角色差异字段）';

-- 管理员表（角色差异字段：仅分级）
-- SUPER 保护用于防止「普通管理员删掉最后一个超管」导致治理功能锁死
CREATE TABLE IF NOT EXISTS admin (
  id          BIGINT      NOT NULL COMMENT '主键 = sys_user.id',
  admin_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'SUPER/NORMAL',
  created_at  DATETIME,
  updated_at  DATETIME,
  PRIMARY KEY (id)
) COMMENT '管理员表（角色差异字段）';

-- ---------- 量表配置 ----------
CREATE TABLE IF NOT EXISTS scale (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(16) NOT NULL UNIQUE COMMENT 'PHQ9/GAD7/PSS10',
  name        VARCHAR(64) NOT NULL,
  dimension   VARCHAR(32) COMMENT '测量维度',
  max_score   INT,
  description VARCHAR(255),
  status      TINYINT DEFAULT 1,
  created_at  DATETIME
) COMMENT '心理量表';

CREATE TABLE IF NOT EXISTS scale_item (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  scale_id      BIGINT NOT NULL,
  item_no       INT NOT NULL,
  content       VARCHAR(255) NOT NULL,
  crisis_flag   TINYINT DEFAULT 0 COMMENT '是否危机项',
  reverse_score TINYINT DEFAULT 0 COMMENT '是否反向计分',
  KEY idx_scale (scale_id)
) COMMENT '量表题目';

CREATE TABLE IF NOT EXISTS scale_option (
  id      BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  content VARCHAR(64) NOT NULL,
  score   INT NOT NULL,
  sort    INT DEFAULT 0,
  KEY idx_item (item_id)
) COMMENT '量表选项';

CREATE TABLE IF NOT EXISTS scoring_rule (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  scale_id   BIGINT NOT NULL,
  min_score  INT NOT NULL,
  max_score  INT NOT NULL,
  raw_level  VARCHAR(16) NOT NULL COMMENT '原始等级',
  risk_level VARCHAR(8)  NOT NULL COMMENT '系统等级 A/B/C/D',
  sort       INT DEFAULT 0,
  KEY idx_scale (scale_id)
) COMMENT '计分切分规则';

-- ---------- 测评 ----------
CREATE TABLE IF NOT EXISTS assessment (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_id  BIGINT NOT NULL,
  scale_id     BIGINT NOT NULL,
  total_score  INT NOT NULL,
  raw_level    VARCHAR(16),
  risk_level   VARCHAR(8) COMMENT 'A/B/C/D',
  crisis_flag  TINYINT DEFAULT 0,
  answer_json  TEXT COMMENT '作答明细 itemId->optionId',
  created_at   DATETIME,
  KEY idx_res_time (resident_id, created_at)
) COMMENT '心理测评记录';

CREATE TABLE IF NOT EXISTS assessment_answer (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  assessment_id BIGINT NOT NULL,
  item_id       BIGINT NOT NULL,
  item_no       INT,
  score         INT,
  option_id     BIGINT,
  KEY idx_assessment (assessment_id)
) COMMENT '测评作答明细';

-- ---------- 预警 ----------
CREATE TABLE IF NOT EXISTS alert_record (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_id   BIGINT NOT NULL,
  assessment_id BIGINT,
  alert_type    VARCHAR(16) NOT NULL COMMENT 'THRESHOLD/TREND/CRISIS',
  alert_level   VARCHAR(8)  NOT NULL COMMENT 'B/C/D',
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  handler_id    BIGINT,
  handle_note   TEXT,
  created_at    DATETIME,
  updated_at    DATETIME,
  KEY idx_res_status (resident_id, status)
) COMMENT '预警工单';

CREATE TABLE IF NOT EXISTS alert_status_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  alert_id    BIGINT NOT NULL,
  from_status VARCHAR(16),
  to_status   VARCHAR(16),
  operator_id BIGINT,
  remark      VARCHAR(255),
  created_at  DATETIME,
  KEY idx_alert (alert_id)
) COMMENT '预警状态流转日志';

-- ---------- 问诊 ----------
CREATE TABLE IF NOT EXISTS consult_session (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_id   BIGINT NOT NULL,
  doctor_id     BIGINT COMMENT 'AI问诊为NULL',
  consult_type  VARCHAR(8) NOT NULL COMMENT 'PHONE/DOCTOR/AI',
  status        VARCHAR(8) DEFAULT 'OPEN',
  assessment_id BIGINT,
  alert_id      BIGINT,
  summary       TEXT,
  created_at    DATETIME,
  closed_at     DATETIME,
  KEY idx_res (resident_id)
) COMMENT '问诊会话';

CREATE TABLE IF NOT EXISTS consult_message (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id  BIGINT NOT NULL,
  sender      VARCHAR(8) NOT NULL COMMENT 'RESIDENT/DOCTOR/AI',
  sender_id   BIGINT,
  content     TEXT,
  crisis_flag TINYINT DEFAULT 0,
  created_at  DATETIME,
  KEY idx_session (session_id)
) COMMENT '问诊消息';

CREATE TABLE IF NOT EXISTS recommendation_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_id BIGINT NOT NULL,
  session_id  BIGINT,
  doctor_id   BIGINT NOT NULL,
  reason      VARCHAR(255) NOT NULL COMMENT '推荐依据',
  accepted    TINYINT DEFAULT 0,
  created_at  DATETIME,
  KEY idx_res (resident_id)
) COMMENT '医生推荐日志';

CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  title      VARCHAR(128),
  content    VARCHAR(512),
  type       VARCHAR(16) COMMENT 'ALERT/CONSULT/SYSTEM',
  read_flag  TINYINT DEFAULT 0,
  created_at DATETIME,
  KEY idx_user (user_id)
) COMMENT '站内信';

-- =============================================================
-- 量表种子数据
-- =============================================================
INSERT INTO scale (id, code, name, dimension, max_score, description, status, created_at) VALUES
(1, 'PHQ9', 'PHQ-9 抑郁筛查量表', '抑郁', 27, '患者健康问卷，9 题，筛查抑郁', 1, NOW()),
(2, 'GAD7', 'GAD-7 焦虑筛查量表', '焦虑', 21, '广泛性焦虑量表，7 题', 1, NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- PHQ-9 题目（item 9 为危机项）
INSERT INTO scale_item (id, scale_id, item_no, content, crisis_flag, reverse_score) VALUES
(101,1,1,'做事时提不起劲或没有兴趣',0,0),
(102,1,2,'感到心情低落、沮丧或绝望',0,0),
(103,1,3,'入睡困难、睡不安稳或睡眠过多',0,0),
(104,1,4,'感觉疲倦或没有活力',0,0),
(105,1,5,'食欲不振或吃太多',0,0),
(106,1,6,'觉得自己很糟，或觉得自己很失败，或让自己或家人失望',0,0),
(107,1,7,'对事物专注有困难，例如阅读报纸或看电视时',0,0),
(108,1,8,'动作或说话速度缓慢到别人已经察觉，或正好相反——烦躁或坐立不安',0,0),
(109,1,9,'有不如死掉或用某种方式伤害自己的念头',1,0);

-- GAD-7 题目
INSERT INTO scale_item (id, scale_id, item_no, content, crisis_flag, reverse_score) VALUES
(201,2,1,'感觉紧张、焦虑或急切',0,0),
(202,2,2,'不能停止或控制担忧',0,0),
(203,2,3,'对各种各样的事情担忧过多',0,0),
(204,2,4,'很难放松下来',0,0),
(205,2,5,'由于不安而无法静坐',0,0),
(206,2,6,'变得容易烦恼或急躁',0,0),
(207,2,7,'感到似乎将有可怕的事情发生而害怕',0,0);

-- 选项（PHQ-9 与 GAD-7 通用 0-3 分四档）
INSERT INTO scale_option (item_id, content, score, sort)
SELECT item_id, c, s, s FROM (
  SELECT 101 AS item_id UNION ALL SELECT 102 UNION ALL SELECT 103 UNION ALL SELECT 104 UNION ALL SELECT 105
  UNION ALL SELECT 106 UNION ALL SELECT 107 UNION ALL SELECT 108 UNION ALL SELECT 109
  UNION ALL SELECT 201 UNION ALL SELECT 202 UNION ALL SELECT 203 UNION ALL SELECT 204 UNION ALL SELECT 205
  UNION ALL SELECT 206 UNION ALL SELECT 207
) items
CROSS JOIN (
  SELECT '完全不会' AS c, 0 AS s UNION ALL
  SELECT '好几天', 1 UNION ALL
  SELECT '一半以上的天数', 2 UNION ALL
  SELECT '几乎每天', 3
) opts;

-- PHQ-9 切分规则
INSERT INTO scoring_rule (scale_id, min_score, max_score, raw_level, risk_level, sort) VALUES
(1,0,4,'无','A',1),
(1,5,9,'轻度','A',2),
(1,10,14,'中度','B',3),
(1,15,19,'中重度','C',4),
(1,20,27,'重度','D',5);

-- GAD-7 切分规则
INSERT INTO scoring_rule (scale_id, min_score, max_score, raw_level, risk_level, sort) VALUES
(2,0,4,'极轻','A',1),
(2,5,9,'轻度','A',2),
(2,10,14,'中度','B',3),
(2,15,21,'重度','C',4);
