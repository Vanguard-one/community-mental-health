-- =============================================================
-- V12  筛查表管理（管理端：启用 / 禁用 / 删除）
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- 本脚本只做一件事：给 scale 表加逻辑删除列。
-- 加完之后，管理端才能对四张筛查表执行「禁用 / 启用 / 删除」。
-- =============================================================
--
-- 【为什么量表不能物理删除】
--
--   引用关系：
--     assessment.scale_id          → scale.id
--     assessment_answer.item_id    → scale_item.id
--     assessment_answer.option_id  → scale_option.id
--
--   物理删掉一行 scale，AssessmentRecordMapper.xml 里那两条
--   `JOIN scale s ON s.id = a.scale_id` 会直接查不出数据 ——
--   居民的测评历史列表会变空、医生工单右栏的纵向对比会断、逐题明细会丢卷名。
--   这些是全系统最不能丢的数据，而「删除一张量表」是个运营动作，
--   不该有这种破坏力。
--
--   所以删除 = 软删除：
--     · 居民端 /scale/list 查不到（status 已置 0 且 is_deleted=1）
--     · 不能再提交测评（AssessmentService.submit 校验 status）
--     · 管理端列表不展示（BaseMapper 自动追加 is_deleted = 0）
--     · 历史测评照常可看（XML 自定义 JOIN 不受逻辑删除插件影响）
--
-- 【为什么 status 也要置 0】
--
--   逻辑删除只作用于 MyBatis-Plus 的 BaseMapper 方法。报表 SQL、原生查询、
--   将来某个忘了加条件的 select 仍可能扫到这一行。删除时顺手把 status 置 0，
--   等于给所有没走逻辑删除的读路径上了同一把锁。
--
-- =============================================================
-- 执行前检查（可先跑这两条看清现状）
-- =============================================================
--   SHOW COLUMNS FROM scale;
--   SELECT id, code, name, status FROM scale ORDER BY id;
--
-- 预期：四张筛查表 PHQ9 / GAD7 / PHQ15 / PSQI，status 均为 1。
-- =============================================================

USE psycare;

-- -------------------------------------------------------------
-- 加列（幂等：已存在则跳过，可重复执行）
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema + 动态 SQL 兜住
-- -------------------------------------------------------------
SET @ddl := IF(
    (SELECT COUNT(*)
       FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME   = 'scale'
        AND COLUMN_NAME  = 'is_deleted') = 0,
    'ALTER TABLE `scale` ADD COLUMN `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0 未删 1 已删''',
    'SELECT ''scale.is_deleted 已存在，跳过'' AS skipped'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------
-- 验证
-- -------------------------------------------------------------
SHOW COLUMNS FROM scale;

SELECT s.id,
       s.code,
       s.name,
       s.status,
       s.is_deleted,
       (SELECT COUNT(*) FROM scale_item  i WHERE i.scale_id = s.id) AS item_count,
       (SELECT COUNT(*) FROM assessment  a WHERE a.scale_id = s.id) AS assessment_count
  FROM scale s
 ORDER BY s.id;

-- -------------------------------------------------------------
-- 回滚（仅在确实要撤销本次变更时手工执行）
-- -------------------------------------------------------------
--   ALTER TABLE `scale` DROP COLUMN `is_deleted`;
--
--   ⚠️ 回滚前必须先清理软删数据，否则「已删除的量表」会重新出现在管理端列表里：
--   SELECT * FROM scale WHERE is_deleted = 1;
--   -- 确认这些行确实不需要了，再决定是恢复（is_deleted=0）还是物理删除
-- =============================================================
