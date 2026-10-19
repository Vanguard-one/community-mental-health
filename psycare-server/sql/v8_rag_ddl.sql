-- =============================================================
-- V8  RAG 知识库：kb_document + kb_chunk
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- 【文件名的由来】计划文档 8.2 节写的是 `v4_rag_ddl.sql`，但 `v4` 已被
--   `v4_ai_message_finish_reason.sql` 占用（Phase 0 的 finish_reason 加列）。
--   编号按**实际落盘顺序**顺延为 v8，避免两个不同内容共用 `v4` 前缀。
--
-- 【两张表的分工：事实源 vs 索引层】
--   MySQL 是**事实源**，Milvus 是**可重建的索引层**（计划 2.3 的核心原则）。
--   所以正文只在这里写一次，Milvus 里存的只是「子块向量 + 检索元数据」；
--   Milvus 整个 drop 掉都能靠本表 rebuild 回来。这也是本脚本不加任何
--   与向量相关的列（vector / norm）的原因 —— 向量不属于事实。
--
-- 【父子分块，但只存一张分块表】
--   检索命中子块后需要把**父块正文**喂给模型（子块 300–500 字太碎，
--   上下文不完整）。两种存法：
--     · 父子各一张表  → 两套 CRUD、两套删除逻辑，重建时还要保证两者一致；
--     · 一张表加 chunk_type + parent_id（**本方案**）→ 只多一列，
--       「删除某文档的全部分块」永远是 `WHERE doc_id = ?` 一条语句。
--   代价是父块也要占一行存储 —— 可接受，因为父块数只有子块的 1/4 左右。
--
-- 【为什么父块不进 Milvus（架构约束，不是优化）】
--   ⚠️ 2026-10-08 订正：下面那条「版本不支持 NULL」的理由**已失效** ——
--     本机实测版本是 Milvus **v3.0.0**，它**支持全部六种向量类型的 NULL**（搜索自动跳过 NULL 行）。
--     **结论仍保留**，但理由换成主动的：父块本来就不参与向量检索，不进 collection 更省索引与内存，
--     且 parent_id 回表这条路已经铺好。将来若真有「父块也要被过滤/统计」的需求，可以再议。
--   父块没有向量（不做向量化），塞进同一个 collection 就必须给它一个假向量，
--   而假向量会污染近邻检索结果。故父块只留 MySQL，命中子块后用 parent_id 回表取。
--   回表是一次 `IN` 查询批量取 3–5 个父块，成本可忽略。
--   历史理由（已不适用，留作记录）：Milvus 2.6.2–2.6.17 向量字段不可为 NULL，曾计划卡 2.6.19+。
--
-- 【为什么必须记 embed_model 与 dim】
--   同一个问题问过两次、两次答案不一样的代价只是观感；
--   而**换了向量模型却混用旧向量**的代价是检索结果整体劣化，且**毫无报错** ——
--   不同模型/维度的向量在同一空间里比较，近邻关系是随机的。
--   把 (embed_model, dim) 钉在文档行上，rebuild 脚本才能回答
--   「哪些文档是用旧模型建的、需要重跑」，而不是全量重跑或全量漏跑。
--   ⚠️ 维度一旦变更，**所有已入库向量都必须重新生成**。
--
-- 【为什么 kb_chunk 没有 updated_at】
--   分块**是不可变的**：内容变了就是一次重建（按 doc_id 先删后插），
--   不存在「原地改一个分块」的操作。给一个永远不被更新的列，只会让人以为
--   它有意义。文档层的可变状态（status / chunk_count）在 kb_document 上。
--
-- 约定沿用：本库**无外键约束**（删改列前须全库 grep 列名）；
--          逻辑删除统一 `is_deleted`（与 mybatis-plus logic-delete-field 对齐）。
--
-- 执行前置：本脚本**幂等**（CREATE TABLE IF NOT EXISTS），可重复执行。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

-- ---------- 1. 文档表（索引任务的状态机挂在它上面） ----------
CREATE TABLE IF NOT EXISTS kb_document (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  title        VARCHAR(256) NOT NULL COMMENT '文档标题（引用卡片展示）',
  source_type  VARCHAR(32)  NOT NULL DEFAULT 'SYNTHETIC'
               COMMENT 'SYNTHETIC=AI合成(仅验链路) / AUTHORITATIVE=权威 / UPLOAD=人工上传',
  source_path  VARCHAR(512) COMMENT '原始文件路径；合成语料记生成脚本与 topics 来源',
  publish_year INT          COMMENT '发布年份（医疗时效性加权）',
  -- 状态机：PENDING --索引开始--> INDEXING --双写成功--> READY
  --                                            --任一步失败--> FAILED（error_msg 记原因）
  -- 允许 FAILED 重跑：rebuild 会把 FAILED 当 PENDING 处理，不需要人工改库。
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
               COMMENT 'PENDING/INDEXING/READY/FAILED',
  chunk_count  INT          NOT NULL DEFAULT 0
               COMMENT '子块数 = 该文档在 Milvus 中的向量条数（校验脚本比对用）',
  embed_model  VARCHAR(64)  COMMENT '建索引时使用的向量模型',
  dim          INT          COMMENT '向量维度（与 embed_model 成对，缺一不可）',
  error_msg    VARCHAR(512) COMMENT '失败原因（截断保存，完整堆栈走日志）',
  is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删除 1已删除（逻辑删除）',
  created_at   DATETIME,
  updated_at   DATETIME,
  deleted_at   DATETIME     COMMENT '删除时间（审计留痕）',
  -- 与 sys_user 同款写法：唯一索引只约束「未删除行」。
  -- 合成语料重跑时靠它做幂等（INSERT ... ON DUPLICATE KEY UPDATE），
  -- 若写成 UNIQUE(title)，软删后的同名文档会让重跑直接失败。
  UNIQUE KEY uk_alive_title ((IF(is_deleted = 0, title, NULL))) COMMENT '仅未删除行标题唯一',
  KEY idx_status (status)
) COMMENT '知识库文档（事实源；Milvus 只是它的可重建索引）';

-- ---------- 2. 分块表（父子块同表，靠 chunk_type 区分） ----------
CREATE TABLE IF NOT EXISTS kb_chunk (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  doc_id       BIGINT       NOT NULL COMMENT '所属文档 = kb_document.id',
  parent_id    BIGINT       COMMENT '父块 id；自身即父块时为 NULL',
  chunk_type   VARCHAR(8)   NOT NULL DEFAULT 'CHILD' COMMENT 'PARENT=上下文 / CHILD=检索单元',
  ordinal      INT          NOT NULL DEFAULT 0
               COMMENT '文档内顺序。重建索引时按它推回原文顺序 —— 不依赖自增 id 的连续性',
  section      VARCHAR(256) COMMENT '章节路径，如「第 2 节 条目解读 / 2.3 第 9 题」（引用溯源）',
  content      TEXT         NOT NULL COMMENT '分块正文',
  char_count   INT          COMMENT '字符数（中文场景按字算，不用字节）',
  token_count  INT          COMMENT '估算 token 数（上下文预算控制用）',
  content_hash CHAR(32)     COMMENT '正文 MD5，供 MySQL↔Milvus 校验脚本比对与重复导入去重',
  page_from    INT          COMMENT '起始页（PDF 溯源；合成语料为 NULL）',
  page_to      INT          COMMENT '结束页',
  is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删除 1已删除（逻辑删除）',
  created_at   DATETIME,
  KEY idx_doc (doc_id),
  KEY idx_parent (parent_id)
) COMMENT '知识库分块（PARENT=生成上下文且不进 Milvus；CHILD=进 Milvus 的检索单元）';

-- ---------- 3. 校验 ----------
SELECT TABLE_NAME, TABLE_COMMENT
  FROM INFORMATION_SCHEMA.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('kb_document', 'kb_chunk')
 ORDER BY TABLE_NAME;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('kb_document', 'kb_chunk')
 ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- 期望：两表均为 0 行（本次只建结构，语料由 gen_synthetic_corpus.py 生成后灌入）
SELECT 'kb_document' AS t, COUNT(*) AS cnt FROM kb_document
UNION ALL
SELECT 'kb_chunk'    AS t, COUNT(*) AS cnt FROM kb_chunk;
