-- =============================================================
-- V4  consult_message 增加 finish_reason（AI 回复的收尾方式）
-- 目标库：MySQL 8.0.13+，库名 psycare
-- 关联：AI助手开发计划.md § 5.8 ⑤
--
-- 【为什么需要这一列】
--   AI 回复的落库时机从 doOnComplete 改为 doFinally 后，「这条回复是完整生成的、
--   还是客户端中途断开被截断的」必须能区分 —— 否则运维看到的是一堆长度不一的
--   回复，无法判断其中哪些是异常截断（截断率是流式体验的核心指标之一）。
--
--   DONE        正常流式完成
--   INTERRUPTED 客户端断开 / 取消（内容已落已生成部分）
--   CRISIS      落库前命中危机词，内容被替换为固定危机文案
--   ERROR       上游 LLM 抛错（已生成部分仍落库）
--   NULL        非 AI 消息（居民/医生发的），本列不适用
--
-- 【为什么加在 consult_message 而不是等 ai_usage_log】
--   ai_usage_log（Phase 1）记的是**调用元数据**（token/延迟/error_code），
--   回答的是「这次调用怎么样」；finish_reason 记在**消息**上，回答的是
--   「这条回复本身完不完整」。两者是不同维度，不能互相替代 ——
--   消息可能因为进程退出而根本没产生调用日志。
--
-- 执行前置：本脚本**幂等**（用 INFORMATION_SCHEMA 预判），可重复执行。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

SET @ddl := (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE consult_message
       ADD COLUMN finish_reason VARCHAR(16) NULL
       COMMENT ''AI 回复收尾方式：DONE/INTERRUPTED/CRISIS/ERROR，非 AI 消息为 NULL''
       AFTER crisis_flag',
    'DO 0'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'consult_message'
    AND COLUMN_NAME = 'finish_reason'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史行一律回填 DONE：改造前落库的回复都发生在 doOnComplete，
-- 即「必须完整生成才会写进来」，语义上等价于 DONE。
UPDATE consult_message
   SET finish_reason = 'DONE'
 WHERE sender = 'AI' AND finish_reason IS NULL;

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'consult_message'
   AND COLUMN_NAME = 'finish_reason';

SELECT finish_reason, COUNT(*) AS cnt
  FROM consult_message
 GROUP BY finish_reason;
