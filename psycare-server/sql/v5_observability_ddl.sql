-- =============================================================
-- V5  可观测性两张表：ai_usage_log / ai_tool_call_log
-- 目标库：MySQL 8.0.13+，库名 psycare
-- 关联：AI助手开发计划.md § 5.11 可观测性与成本 / § 8.2 SQL 脚本
--
-- 【为什么 Phase 1 就要落表，而不是等有了问题再补】
--   「首 token 延迟」「降级率」「缓存命中率」这三个指标，**只能从历史数据里算**。
--   事后再想加，改造前的调用已经没有任何记录，基线永远建不起来。
--   而且它们各对应一个真实决策：
--     · 首 token P95 > 2.5s     → 说明该动 effort 档位或换更快的模型
--     · 降级率 > 2%             → 上游稳定性问题，不是「偶发」
--     · 缓存命中率 < 30%        → 提示词前缀不稳定，改一次省下的钱超过其他所有优化
--
-- 【为什么 cached_tokens 要单独一列】
--   缓存命中的输入 token 单价约为未命中的 1/10。合成一个 prompt_tokens 列，
--   就等于把「前缀是否稳定」这个唯一可量化的降本指标抹平 —— 报表里只看得到
--   总 token 涨了，看不出是「用户变多了」还是「前缀被打乱了」。
--
-- 【为什么 reasoning_tokens 也要单独一列（本脚本相对计划新增）】
--   DeepSeek-V4 是推理模型，思考 token 按输出价计费且**完全不可见**。
--   不单独记，就会看到「用户只问了 20 字，completion 却有 3000 token」而无从解释。
--   实测：同一句「说三个字」的请求，思考用了 47~507 token 不等，波动极大。
--
-- 【为什么 ai_tool_call_log 现在就建（Phase 5 才用）】
--   建表成本近乎为零，而它约束的是**入参脱敏**这条安全约定：
--   args_digest 存的是「脱敏 + 截断」后的摘要，不存原始入参。
--   表结构定下来，Phase 5 写工具时就不会有人顺手把原始参数塞进日志。
--
-- 执行前置：本脚本**幂等**（CREATE TABLE IF NOT EXISTS），可重复执行。
-- 项目惯例：先演练再上真库 ——
--   python .workbuddy/scripts/run_sql_file.py psycare-server/sql/v5_observability_ddl.sql --db psycare_ddl_check
--   python .workbuddy/scripts/run_sql_file.py psycare-server/sql/v5_observability_ddl.sql
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 1. ai_usage_log：每次 LLM 调用的元数据
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_usage_log (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  session_id        BIGINT       NULL COMMENT '所属会话；调用发生在会话建立前时为 NULL',
  user_id           BIGINT       NOT NULL COMMENT '发起人；用于单用户成本归集与配额',
  model             VARCHAR(64)  NOT NULL COMMENT '实际使用的模型名，便于换模型后对比成本',
  effort            VARCHAR(8)   NULL COMMENT '思考档位 low/high/max；NULL 表示用服务端默认',
  prompt_tokens     INT          NOT NULL DEFAULT 0,
  cached_tokens     INT          NOT NULL DEFAULT 0 COMMENT '缓存命中的输入 token，单价约为未命中的 1/10',
  completion_tokens INT          NOT NULL DEFAULT 0,
  reasoning_tokens  INT          NOT NULL DEFAULT 0 COMMENT '思考 token（推理模型按输出价计费且不可见）',
  first_token_ms    INT          NULL COMMENT '首帧延迟；NULL 表示一帧都没收到',
  total_ms          INT          NOT NULL DEFAULT 0,
  retrieved_count   TINYINT      NOT NULL DEFAULT 0 COMMENT 'RAG 命中的知识块数，Phase 4 起有意义',
  error_code        VARCHAR(32)  NULL COMMENT '见 LlmErrorCode；NULL 表示成功',
  error_detail      VARCHAR(512) NULL COMMENT '脱敏截断后的错误摘要，不存完整上游响应',
  created_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, created_at),
  KEY idx_error (error_code),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 调用用量与延迟';

-- -------------------------------------------------------------
-- 2. ai_tool_call_log：工具调用审计（Phase 5 写入）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_tool_call_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  session_id  BIGINT       NULL,
  user_id     BIGINT       NOT NULL,
  tool_name   VARCHAR(64)  NOT NULL,
  args_digest VARCHAR(512) NULL COMMENT '入参「脱敏 + 截断」摘要；严禁存原始入参',
  ok          TINYINT      NOT NULL COMMENT '1 成功 0 失败',
  latency_ms  INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_session (session_id),
  KEY idx_tool_time (tool_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 工具调用审计';

-- -------------------------------------------------------------
-- 3. 校验
-- -------------------------------------------------------------
SELECT TABLE_NAME, TABLE_COMMENT
  FROM INFORMATION_SCHEMA.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('ai_usage_log', 'ai_tool_call_log');

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'ai_usage_log'
 ORDER BY ORDINAL_POSITION;
