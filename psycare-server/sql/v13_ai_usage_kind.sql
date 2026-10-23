-- =============================================================
-- V13  ai_usage_log 增加 kind 列：区分「主对话 / 会话摘要 / 追问建议」
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- 【为什么需要这一列】
--   在它之前，ai_usage_log 只能表达「一次调用」，但实际有三类调用在计费：
--     CHAT      主对话（SSE 流式，用户可见）—— 唯一有记录的一类
--     SUMMARY   会话摘要压缩（异步、非流式）—— 一条记录都没有
--     FOLLOWUP  追问建议生成（done 之后、非流式）—— 一条记录都没有
--   后两类的正文不需要给用户看，改造前连 usage 都被丢掉了
--   （OpenAiCompatClient.complete 用 .map(extractContent) 只取正文）。
--   于是「一轮问答实际花多少 token」的答案永远是低估的：
--   用户聊得越久，摘要调用越多；每轮问答还多一次追问建议 —— 两者都只体现在账单上。
--
-- 【为什么默认值给 'CHAT' 而不是 NULL】
--   历史行全部来自主对话，DEFAULT 'CHAT' 让它们自动归位，
--   不需要写一条 UPDATE 回填（回填会锁表，且这张表只增不减）。
--
-- 【为什么是 VARCHAR(16) 而不是 ENUM】
--   调用类型是「生产者侧的分类」，还会继续加（Phase 5 的工具调用、可能的多模态描述）。
--   ENUM 加值要 ALTER TABLE，而这张表是高频写入的日志表；
--   VARCHAR + 应用层常量（AiUsageRecorder.KIND_*）成本更低，且改分类不必碰 DDL。
--   16 长度对 CHAT / SUMMARY / FOLLOWUP / TOOL_CALL 都够。
--
-- 【为什么不做索引】
--   目前的用法是「按 session_id 或时间范围查明细」，kind 只是过滤条件之一。
--   单表数据量与查询频率都还远不到需要组合索引的程度，
--   先加列观察真实查询形态，再加索引比现在猜更省事。
--
-- 执行前置：本脚本**幂等**。MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
--   故用 information_schema 判断后再动态执行，重复跑是空操作。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'psycare'
    AND TABLE_NAME   = 'ai_usage_log'
    AND COLUMN_NAME  = 'kind'
);

SET @ddl := IF(@has_col = 0,
  'ALTER TABLE ai_usage_log ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT ''CHAT'' COMMENT ''调用类型：CHAT 主对话 / SUMMARY 会话摘要 / FOLLOWUP 追问建议'' AFTER user_id',
  'SELECT ''kind 已存在，跳过'' AS skipped'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证：应看到 kind，类型 varchar(16)，NOT NULL，默认 CHAT
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'psycare' AND TABLE_NAME = 'ai_usage_log' AND column_name = 'kind';

-- 验证：历史行应全部归为 CHAT
SELECT kind, COUNT(*) AS rows_cnt
FROM ai_usage_log
GROUP BY kind;
