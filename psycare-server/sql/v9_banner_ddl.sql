-- =============================================================
-- V9  首页轮播 banner：banner
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- 【为什么必须新建表，而不是复用 notification】
--   首页右下角原来展示的是 `notification` 表的数据（按 user_id 一对一推送、
--   带 read_flag 已读标记）。而 banner 的语义完全不同：
--     · 面向**全体访客**，不区分用户 → 不需要 user_id、不需要 read_flag；
--     · 需要**图片 + 跳转链接** → notification 没有这两个字段；
--     · 需要**排序**和**启用/停用** → notification 没有 status/sort；
--     · 需要**展示时间窗口**（只在一定时间内露出）→ notification 没有。
--   硬塞进 notification 会得到一张「一半列永远为 NULL」的表，且两个业务的
--   查询条件会互相污染（居民看通知要额外 `AND user_id = ?`，banner 要
--   `AND user_id IS NULL`），所以**独立建表**是唯一干净的做法。
--
-- 【为什么不删 notification 表】
--   「我的消息 / 通知」页面（`/notifications`）仍在用这张表，只是**首页右下角
--   不再读它**。本脚本只新增表，不动 notification。
--
-- 【字段设计对齐的先例】
--   · sort      INT DEFAULT 0（升序，值小的在前）← 对齐 `scale_option.sort` / `scoring_rule.sort`
--   · status    TINYINT DEFAULT 1（1启用 0停用）← 对齐 `scale.status`
--   · is_deleted / created_at / updated_at ← 对齐 `kb_document`（v8 最新范式）
--
-- 【关于展示时间窗口 start_at / end_at】
--   两者**均为可空**，语义是「开放区间」：
--     · start_at IS NULL → 立即生效（不设起始时间）
--     · end_at   IS NULL → 永不过期（不设结束时间）
--     · 两者都 NULL → 永久展示（默认状态，管理端留空即可）
--   这样管理端「展示时间」是可选项，不填就是长期有效，避免强制填时间造成的
--   意外过期（一个刚建的 banner 因为 end_at 默认值变成了过去时间而看不见）。
--
-- 【为什么不加 UNIQUE(title)】
--   banner 是运营物料，「双十一专题」这类标题重复出现很常见，且软删后重建
--   会撞唯一键。故不加唯一约束（与 kb_document 的 uk_alive_title 场景不同）。
--
-- 约定沿用：本库**无外键约束**；逻辑删除统一 `is_deleted`
--           （与 mybatis-plus logic-delete-field 对齐）。
--
-- 执行前置：本脚本**幂等**（CREATE TABLE IF NOT EXISTS），可重复执行。
-- =============================================================
USE psycare;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS banner (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  title      VARCHAR(128) NOT NULL COMMENT '标题（管理端列表展示 + 图片 alt 文案）',
  image_url  VARCHAR(512) NOT NULL
             COMMENT '图片地址。本地上传后由后端返回 /api/uploads/banner/xxx.png，前端直接拼 baseURL 使用',
  link_url   VARCHAR(512) COMMENT '点击跳转链接。支持站内相对路径（/scale-list）或完整 URL；为空则不可点击',
  sort       INT          NOT NULL DEFAULT 0
             COMMENT '排序，值小的在前（升序）。管理端「上移/下移」交换相邻两行的 sort 值',
  status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用（停用后首页不展示，但数据保留）',
  start_at   DATETIME     COMMENT '展示开始时间；NULL=立即生效',
  end_at     DATETIME     COMMENT '展示结束时间；NULL=永不过期',
  is_deleted TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删除 1已删除（逻辑删除）',
  created_at DATETIME,
  updated_at DATETIME,
  -- 首页查询固定是 `WHERE status=1 AND is_deleted=0 AND 时间窗口内 ORDER BY sort`，
  -- 这条联合索引直接覆盖最左的 status + sort，软删过滤交给回表判断即可。
  KEY idx_status_sort (status, sort)
) COMMENT '首页轮播 banner（面向全体访客的运营物料）';

-- ---------- 校验 ----------
SELECT TABLE_NAME, TABLE_COMMENT
  FROM INFORMATION_SCHEMA.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'banner';

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'banner'
 ORDER BY ORDINAL_POSITION;

-- 期望：本次只建结构，0 行（演示数据由 v10 或管理端录入）
SELECT COUNT(*) AS banner_rows FROM banner;
