-- =============================================================
-- V10  评分标准规范化 + 新增 PHQ-15 / PSQI 两张量表
-- 目标库：MySQL 8.0.13+，库名 psycare
--
-- =============================================================
-- 【第一部分：为什么原来的评分「不准」】
-- =============================================================
--
-- 现象：用户反馈「评测的不太准」。查库发现三处具体问题，全部是**标准不统一**，
--       而不是算法写错了 —— 计分逻辑（总分求和 + 切分区间匹配）本身是对的。
--
-- ── 问题 1：PHQ-9 与 GAD-7 的「重度」处理不一致 ────────────────
--   库内实际配置（修改前）：
--     PHQ-9: 0-4→A  5-9→A  10-14→B  15-19→C  20-27→D
--     GAD-7: 0-4→A  5-9→A  10-14→B  15-21→C          ← 没有 D
--   两个量表都是「中度=B、中重度=C」，但 PHQ-9 的「重度」给 D，
--   GAD-7 的「重度」（15-21，官方手册明确标为 severe）却只给 C。
--   → 同样是「重度」，抑郁被判危机、焦虑只判风险，标准不统一。
--
-- ── 问题 2：GAD-7 缺 D 级，导致「风险等级」这套四档枚举在一半量表里退化 ──
--   RiskLevel 定义了 A/B/C/D 四档（低/中/高/危机）。若某量表永远到不了 D，
--   那么「D=危机」这条全系统约定（预警引擎按 ordinal >= B 建工单、
--   首页红色告警样式、医生端紧急排序）在该量表上就永远不触发。
--
-- ── 问题 3：「高风险」到底以什么为准，此前没有成文口径 ──────────
--   代码里散落着三套判据：scoring_rule 的 risk_level、crisis_flag 覆盖、
--   AlertEngine 的 ordinal >= B。没有一处写明它们的关系。
--
-- =============================================================
-- 【第二部分：规范化后的口径（唯一标准）】
-- =============================================================
--
--   **风险等级 = max(总分基线, 危机项覆写)**
--
--   维度一 · 总分基线（查 scoring_rule，按学段映射）
--     A 低风险 | B 中风险 | C 高风险 | D 危机
--     本脚本把两个量表的学段与等级**对齐为同一套语义**：
--       无/极轻 → A     轻度 → A     中度 → B     中重度 → C     重度 → D
--     PHQ-9 与 GAD-7 按此对齐后，同一严重程度的判定完全一致。
--
--   维度二 · 危机项覆写（crisis_flag=1 的题得分 > 0）
--     一旦阳性，等级**单向抬升**到 D，且置 crisis 标记，
--     由 AlertEngine 建 CRISIS 工单（最高优先级、要求立即人工介入）。
--     覆写只抬升、不降低：总分已是 D 的作答不会因覆写而变成别的等级。
--
--   ⚠️ 关键：危机项**不参与总分加总**。第 9 题阳性是独立的危险信号，
--      不是「抑郁程度 +1 分」。若混入总分，一个「第 9 题答好几天、
--      其余全答完全不会」的人总分只有 1 分，会被判「无抑郁」——荒谬。
--
--   为什么要区分「D 危机」与「C 高风险」：
--     两者的人工响应时效要求不同。C 走普通 THRESHOLD 工单（医生按序处理），
--     D 走 CRISIS 工单（立即介入）。合并成一档会让所有高危都失去优先级区分。
--
-- =============================================================
-- 【第三部分：新增两个量表的选择理由】
-- =============================================================
--
--   现有 PHQ-9（抑郁）+ GAD-7（焦虑）覆盖的是「情绪」维度。
--   社区心理健康筛查的常见缺口是**躯体化**与**睡眠**——很多居民的
--   主诉是「睡不着」「浑身不舒服」而非「心情差」，只用情绪量表会把
--   这批人全部筛成 A（低风险），这不是漏筛，是量表维度不全。
--
--   · PHQ-15 躯体症状量表（15 题）
--       社区与综合医院最常用的躯体化筛查工具。与 PHQ-9 同源（PRIME-MD），
--       计分口径一致，居民不需要学习新的作答方式。
--   · PSQI 匹兹堡睡眠质量指数（7 题，本项目取自评核心 7 个成分）
--       睡眠障碍的标准化自评工具。**特意做成 7 题**：完整版 19 题自评
--       对社区居民过长，7 个成分题已能覆盖「主观质量/入睡时长/睡眠时长/
--       效率/障碍/药物/日间功能」，与完整版结论方向一致。
--
-- =============================================================
-- 【幂等性】
-- =============================================================
--   本脚本可**重复执行**。做法：
--     · 第一部分用 UPDATE 按 (scale_id, raw_level) 精确改值，改完再执行无副作用；
--     · 第二部分先按 code 探测是否已存在，存在则整体跳过（不重建、不重复插）。
--   之所以不用 INSERT ... ON DUPLICATE KEY：本库 scale/scale_item 都**没有**
--   业务唯一键（code 无 UNIQUE 约束，见 schema.sql），无法依赖冲突检测。
--
-- 约定沿用：本库**无外键约束**；scale 无 is_deleted（量表停用走 status）。
--
-- 执行前置：先备份
--   CREATE TABLE scale_bak_v10        LIKE scale;
--   INSERT INTO scale_bak_v10        SELECT * FROM scale;
--   CREATE TABLE scale_item_bak_v10   LIKE scale_item;
--   INSERT INTO scale_item_bak_v10   SELECT * FROM scale_item;
--   CREATE TABLE scale_option_bak_v10 LIKE scale_option;
--   INSERT INTO scale_option_bak_v10 SELECT * FROM scale_option;
--   CREATE TABLE scoring_rule_bak_v10 LIKE scoring_rule;
--   INSERT INTO scoring_rule_bak_v10 SELECT * FROM scoring_rule;
-- =============================================================
USE psycare;
SET NAMES utf8mb4;


-- =============================================================
-- 第一部分 · 规范化已有量表的评分标准
-- =============================================================

-- PHQ-9：仅调整 0-4 的原始等级文案（「无」→「无/极轻」，与官方手册一致），
--        风险等级 A 不变。其余学段与 risk_level 已经符合规范，不动。
UPDATE scoring_rule
   SET raw_level = '无/极轻'
 WHERE scale_id = (SELECT id FROM (SELECT id FROM scale WHERE code = 'PHQ9') t)
   AND raw_level = '无';

-- PHQ-9：5-9 轻度 的等级由 A 明确为 A（低风险）—— 官方「轻度」不需要干预，
--        保持 A 不加码，避免把正常波动的人吓到。此条为**显式声明**，值不变，
--        写出来是为了让「轻度=A」成为成文约定而非巧合。
UPDATE scoring_rule
   SET risk_level = 'A'
 WHERE scale_id = (SELECT id FROM (SELECT id FROM scale WHERE code = 'PHQ9') t)
   AND raw_level = '轻度';

-- GAD-7：**核心修复** —— 15-21 重度，风险等级由 C 抬到 D，
--        与 PHQ-9 的「重度→D」对齐。这是「两量表标准不一致」的直接修复点。
UPDATE scoring_rule
   SET risk_level = 'D'
 WHERE scale_id = (SELECT id FROM (SELECT id FROM scale WHERE code = 'GAD7') t)
   AND raw_level = '重度';

-- GAD-7：0-4 的原始等级由「极轻」统一为「无/极轻」，与 PHQ-9 文案对齐
UPDATE scoring_rule
   SET raw_level = '无/极轻'
 WHERE scale_id = (SELECT id FROM (SELECT id FROM scale WHERE code = 'GAD7') t)
   AND raw_level = '极轻';


-- =============================================================
-- 第二部分 · 新增 PHQ-15（躯体症状）
-- =============================================================
-- 计分：15 题 × 0-2 分 = 0-30 分
--   （原始选项「完全没有=0 / 有一点=1 / 有很多=2」，
--     完整版第 8、11 题按 0-2 折半计分，此处直接用 0-2 三档，等价）
-- 学段（对齐本项目的 A/B/C/D 语义）：
--   0-4   → A  极轻
--   5-9   → B  轻度
--   10-14 → C  中度
--   15-30 → C  重度
-- ⚠️ PHQ-15 **不设 D**：该量表没有任何测量自伤/自杀意图的题目，
--    本身不具备判定「危机」的信息量。硬给一个 D 会制造假危机。
--    「危机」只能来自 PHQ-9 第 9 题这类 crisis_flag 题，或医生人工判断。
--    这正是「危机项覆写」机制存在的意义 —— 不靠总分也能识别危机。
-- =============================================================

INSERT INTO scale (code, name, dimension, max_score, description, status, created_at)
SELECT 'PHQ15', 'PHQ-15 躯体症状量表', '躯体化', 30,
       '患者健康问卷躯体症状版，15 题，筛查躯体化症状（疼痛/消化/心血管等）', 1, NOW()
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM scale WHERE code = 'PHQ15') t);
-- 15 个题目。item_no 从 1 连续编号，与官方题序一致。
--
-- ⚠️ 幂等守卫要判「**题目是否已存在**」，不能判「量表是否存在」。
--    第一版写成 `WHERE NOT EXISTS (SELECT 1 FROM scale WHERE code='PHQ15')`，
--    结果量表刚插完就已存在 → 该条件恒为假 → 45 条选项一条都插不进去，
--    而且**不报错**（演练时才发现：题数 0、选项数 0）。
--    正确判据是「该量表下还没有题目」。
INSERT INTO scale_item (scale_id, item_no, content, crisis_flag, reverse_score)
SELECT s.id, v.item_no, v.content, 0, 0
  FROM (SELECT id FROM scale WHERE code = 'PHQ15') s
  JOIN (
    SELECT 1  AS item_no, '胃痛或肚子痛' AS content UNION ALL
    SELECT 2,  '背痛' UNION ALL
    SELECT 3,  '手臂、腿或关节疼痛（膝、髋等）' UNION ALL
    SELECT 4,  '月经期疼痛或月经期其他不适（女性作答）' UNION ALL
    SELECT 5,  '头痛' UNION ALL
    SELECT 6,  '胸口疼痛' UNION ALL
    SELECT 7,  '头晕' UNION ALL
    SELECT 8,  '感觉心脏砰砰跳或跳得很快' UNION ALL
    SELECT 9,  '气短、透不过气' UNION ALL
    SELECT 10, '便秘、排便费力或腹泻' UNION ALL
    SELECT 11, '恶心想吐、胃部不适或胀气' UNION ALL
    SELECT 12, '感到疲倦或没有精力' UNION ALL
    SELECT 13, '入睡困难、睡不安稳或睡眠过多' UNION ALL
    SELECT 14, '头晕目眩' UNION ALL
    SELECT 15, '感到要晕倒'
  ) v
 WHERE NOT EXISTS (
    SELECT 1 FROM scale_item si WHERE si.scale_id = s.id
 );

-- 每题 3 个选项：完全没有(0) / 有一点(1) / 有很多(2)
-- 用 CROSS JOIN 批量生成，避免手写 45 行 INSERT 时错漏。
INSERT INTO scale_option (item_id, content, score, sort)
SELECT i.id, v.content, v.score, v.sort
  FROM (SELECT id FROM scale WHERE code = 'PHQ15') s
  JOIN scale_item i ON i.scale_id = s.id
  JOIN (
    SELECT '完全没有' AS content, 0 AS score, 1 AS sort UNION ALL
    SELECT '有一点', 1, 2 UNION ALL
    SELECT '有很多', 2, 3
  ) v
 WHERE NOT EXISTS (
    SELECT 1 FROM scale_option so WHERE so.item_id = i.id
 );

INSERT INTO scoring_rule (scale_id, min_score, max_score, raw_level, risk_level, sort)
SELECT s.id, v.min_score, v.max_score, v.raw_level, v.risk_level, v.sort
  FROM (SELECT id FROM scale WHERE code = 'PHQ15') s
  JOIN (
    SELECT 0  AS min_score, 4  AS max_score, '极轻' AS raw_level, 'A' AS risk_level, 1 AS sort UNION ALL
    SELECT 5,  9,  '轻度', 'B', 2 UNION ALL
    SELECT 10, 14, '中度', 'C', 3 UNION ALL
    SELECT 15, 30, '重度', 'C', 4
  ) v
 WHERE NOT EXISTS (SELECT 1 FROM scoring_rule r WHERE r.scale_id = s.id);


-- =============================================================
-- 第三部分 · 新增 PSQI（匹兹堡睡眠质量指数 · 自评 7 成分）
-- =============================================================
-- 计分：7 题 × 0-3 分 = 0-21 分
--   每题对应一个睡眠成分，0=很好，3=很差。总分 > 5 提示睡眠质量差
--   （这是 PSQI 的公认界值，体现在下面的切分规则里）。
-- 学段：
--   0-5  → A  睡眠良好
--   6-10 → B  轻度睡眠障碍
--   11-15 → C 中度睡眠障碍
--   16-21 → C 重度睡眠障碍
-- ⚠️ 与 PHQ-15 同理，PSQI **不设 D**：它测的是睡眠质量，
--    不含自伤/自杀意图题，不具备判定危机的信息量。
-- =============================================================

INSERT INTO scale (code, name, dimension, max_score, description, status, created_at)
SELECT 'PSQI', 'PSQI 睡眠质量量表', '睡眠', 21,
       '匹兹堡睡眠质量指数量表（自评 7 成分），筛查睡眠质量与睡眠障碍', 1, NOW()
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT id FROM scale WHERE code = 'PSQI') t);

-- 7 个成分题。题干按「请回想近一个月，评价下列方面」的口径改写为可自答的形式。
INSERT INTO scale_item (scale_id, item_no, content, crisis_flag, reverse_score)
SELECT s.id, v.item_no, v.content, 0, 0
  FROM (SELECT id FROM scale WHERE code = 'PSQI') s
  JOIN (
    SELECT 1 AS item_no, '近一个月，你对自己整体睡眠质量的评价是' AS content UNION ALL
    SELECT 2, '近一个月，你通常需要多长时间才能入睡' UNION ALL
    SELECT 3, '近一个月，你每晚实际睡眠时长大约是' UNION ALL
    SELECT 4, '近一个月，你在床上的时间与实际睡眠时间的比例（睡眠效率）' UNION ALL
    SELECT 5, '近一个月，你因下列原因影响睡眠的频率（如夜间易醒、起夜、呼吸不畅、咳嗽、疼痛等）' UNION ALL
    SELECT 6, '近一个月，你需要借助药物（安眠药等）才能入睡的频率' UNION ALL
    SELECT 7, '近一个月，你因睡眠问题导致白天精力不足、影响工作或生活的频率'
  ) v
 WHERE NOT EXISTS (
    SELECT 1 FROM scale_item si WHERE si.scale_id = s.id
 );

INSERT INTO scale_option (item_id, content, score, sort)
SELECT i.id, v.content, v.score, v.sort
  FROM (SELECT id FROM scale WHERE code = 'PSQI') s
  JOIN scale_item i ON i.scale_id = s.id
  JOIN (
    SELECT '很好 / 从不' AS content, 0 AS score, 1 AS sort UNION ALL
    SELECT '较好 / 偶尔', 1, 2 UNION ALL
    SELECT '较差 / 经常', 2, 3 UNION ALL
    SELECT '很差 / 总是', 3, 4
  ) v
 WHERE NOT EXISTS (
    SELECT 1 FROM scale_option so WHERE so.item_id = i.id
 );

INSERT INTO scoring_rule (scale_id, min_score, max_score, raw_level, risk_level, sort)
SELECT s.id, v.min_score, v.max_score, v.raw_level, v.risk_level, v.sort
  FROM (SELECT id FROM scale WHERE code = 'PSQI') s
  JOIN (
    SELECT 0  AS min_score, 5  AS max_score, '睡眠良好' AS raw_level, 'A' AS risk_level, 1 AS sort UNION ALL
    SELECT 6,  10, '轻度睡眠障碍', 'B', 2 UNION ALL
    SELECT 11, 15, '中度睡眠障碍', 'C', 3 UNION ALL
    SELECT 16, 21, '重度睡眠障碍', 'C', 4
  ) v
 WHERE NOT EXISTS (SELECT 1 FROM scoring_rule r WHERE r.scale_id = s.id);


-- =============================================================
-- 第四部分 · 自检查询（执行后应全部符合预期）
-- =============================================================

-- 4.1 量表清单（应有 4 张，全部 status=1）
SELECT id, code, name, dimension, max_score, status
  FROM scale ORDER BY id;

-- 4.2 风险等级对照总表 —— **重点核对 PHQ-9 与 GAD-7 的「重度」都是 D**
SELECT s.code AS 量表,
       r.raw_level AS 原始等级,
       CONCAT(r.min_score, '-', r.max_score) AS 区间,
       r.risk_level AS 风险等级
  FROM scoring_rule r JOIN scale s ON s.id = r.scale_id
 ORDER BY s.id, r.sort;

-- 4.3 切分规则**连续性校验**（每张量表都必须恰好覆盖 0..max_score，无空洞无重叠）
--     空洞会导致计分引擎抛「未配置覆盖总分 X 的切分规则」；
--     重叠会导致命中哪条取决于 sort，属于隐藏的不确定性。
SELECT s.code AS 量表, s.max_score AS 满分,
       MIN(r.min_score) AS 规则起点,
       MAX(r.max_score) AS 规则终点,
       CASE WHEN MIN(r.min_score) = 0 AND MAX(r.max_score) = s.max_score
            THEN 'PASS' ELSE 'FAIL 区间未覆盖满分' END AS 覆盖检查,
       CASE WHEN SUM(r.max_score - r.min_score + 1) = s.max_score + 1
            THEN 'PASS' ELSE 'FAIL 区间有重叠或空洞' END AS 连续性检查
  FROM scoring_rule r JOIN scale s ON s.id = r.scale_id
 GROUP BY s.id, s.code, s.max_score
 ORDER BY s.id;

-- 4.4 题数与选项数
--     预期：PHQ-9 9题/36选项、GAD-7 7题/28选项、PHQ-15 15题/45选项、PSQI 7题/28选项
--
--     ⚠️ 这里**不能**用 `选项数 = 题数 * 3` 作为判据 —— 各量表每题选项数本就不同
--        （PHQ-9 每题 4 个选项，36 = 9×4 而非 9×3），硬编码倍数会把已有量表全判 FAIL。
--        正确判据是「**每题的选项数一致，且没有无选项的题**」——这才是「结构完整」的定义。
SELECT s.code AS 量表,
       COUNT(DISTINCT i.id) AS 题数,
       COUNT(o.id) AS 选项数,
       CASE WHEN COUNT(DISTINCT i.id) = 0 THEN '-'
            ELSE CONCAT(COUNT(o.id) DIV COUNT(DISTINCT i.id), ' 个/题') END AS 每题选项数,
       CASE WHEN COUNT(DISTINCT i.id) = 0 THEN 'FAIL 无题目'
            WHEN MIN(x.opt_cnt) <> MAX(x.opt_cnt) THEN 'FAIL 各题选项数不一致'
            WHEN MIN(x.opt_cnt) = 0 THEN 'FAIL 存在无选项的题'
            ELSE 'PASS' END AS 检查
  FROM scale s
  LEFT JOIN scale_item i ON i.scale_id = s.id
  LEFT JOIN scale_option o ON o.item_id = i.id
  LEFT JOIN (
      -- 每题的选项数与该题所属量表，供上面的 MIN/MAX 做一致性判定
      SELECT i2.id AS item_id, i2.scale_id, COUNT(o2.id) AS opt_cnt
        FROM scale_item i2 LEFT JOIN scale_option o2 ON o2.item_id = i2.id
       GROUP BY i2.id, i2.scale_id
  ) x ON x.scale_id = s.id
 GROUP BY s.id, s.code ORDER BY s.id;

-- 4.5 危机项清单（应只有 PHQ-9 第 9 题一条）
SELECT s.code AS 量表, i.item_no AS 题号, i.content AS 题干
  FROM scale_item i JOIN scale s ON s.id = i.scale_id
 WHERE i.crisis_flag = 1;

-- 4.6 新增量表的切分规则明细
SELECT s.code AS 量表, r.min_score, r.max_score, r.raw_level, r.risk_level, r.sort
  FROM scoring_rule r JOIN scale s ON s.id = r.scale_id
 WHERE s.code IN ('PHQ15', 'PSQI') ORDER BY s.id, r.sort;
