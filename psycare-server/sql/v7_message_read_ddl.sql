-- =============================================================
-- V7  consult_message 增加 read_at（消息已读时间）
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- 【为什么需要这一列】
--   在此之前，「有没有消息要处理」是靠「最后一条消息的发送者是不是对端」
--   来近似的（ChatSessionVO.awaitingMyReply）。这个近似有一处说不通：
--   点开对话看过了，只要还没回复，红点就一直亮着 —— 界面在催一件已经做过的事。
--
--   read_at 把「已读」落成数据：NULL = 未读，非 NULL = 该消息被**接收方**读走的时间。
--   消息只属于一个发送者，所以一列就够，不需要 resident_read_at / doctor_read_at
--   成对出现（那是"会话级已读位"的写法，代价是同一秒内到达的消息分不清先后）。
--
-- 【三种 sender 共用这一列】
--   RESIDENT / DOCTOR / AI 都会写 read_at，但**未读统计只走
--   consult_type = 'DOCTOR' 的会话**（见 ChatMapper.xml）：AI 问诊没有红点需求，
--   AI 会话里的消息被标记了也不参与统计。
--
-- 【为什么把存量消息回填为已读】
--   新列对历史行没有事实依据，只能在两个方向里选：
--     · 全部留 NULL（视为未读）→ 上线瞬间两端红点同时亮起一大片，
--       而它们都是早就看过的内容。提醒变误报，用户会立刻学会忽略红点；
--     · 回填为已读 → 红点从**下一条新消息**开始工作，语义干净。
--   取后者：把上线之前的沉默当作「已处理」。
--   想在验收时看到红点，用另一个账号发一条消息即可，不必伪造数据。
--
-- 【为什么不加索引】
--   未读查询的形状是 (session_id = ? AND sender <> ? AND read_at IS NULL)。
--   sender <> ? 是不等值条件，任何以 sender 为第二列的索引都用不上它 ——
--   能命中的只有 session_id 前缀，而 idx_session(session_id) 已经有了。
--   多加一个 (session_id, sender, read_at) 只会增加写入成本，不改变执行计划。
--
-- 执行前置：本脚本**幂等**（INFORMATION_SCHEMA 预判 + 回填只动 NULL 行），可重复执行。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

-- ---------- 1. 加列 ----------
SET @ddl := (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE consult_message
       ADD COLUMN read_at DATETIME NULL
       COMMENT ''接收方读取该消息的时间；NULL = 未读''
       AFTER finish_reason',
    'DO 0'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'consult_message'
    AND COLUMN_NAME = 'read_at'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. 存量回填为已读 ----------
-- 用 created_at 而不是 NOW()：让"读取时刻"落在合理的时间线上，
-- 日后若要算「发出→被读」的时长，也不会得到负值。
UPDATE consult_message
   SET read_at = created_at
 WHERE read_at IS NULL;

-- ---------- 3. 校验 ----------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'consult_message'
   AND COLUMN_NAME = 'read_at';

-- 期望：未读 0 行（存量一律已读），此后新插入的消息才是未读
SELECT CASE WHEN read_at IS NULL THEN '未读' ELSE '已读' END AS state,
       COUNT(*)                                          AS cnt
  FROM consult_message
 GROUP BY state;
