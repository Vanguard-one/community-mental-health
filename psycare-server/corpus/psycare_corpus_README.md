# PsyCare 心理健康与医疗知识语料（psycare_corpus.jsonl）

> 面向管理端**批量导入**的结构化语料。按「每个方向一篇文档」组织，
> 共 **10 篇**，覆盖心理评测工具、常见心理症状、危机识别与干预、常见医疗常识、
> 用药安全、就诊与转介、特殊人群、生活方式干预、医患沟通、康复与随访十个方向。
>
> **本语料不含任何爬虫/编程技术内容**（生成时已做关键词扫描校验，见 §5）。

---

## 结论

- **文件**：`psycare-server/corpus/psycare_corpus.jsonl`（UTF-8，每行一个 JSON 对象，LF 换行）
- **篇数**：10 篇（靠 `doc_title` 分组，同一 `doc_title` 的多行会**合并成一篇**）
- **规模**：约 7.2 万字（子块切分前正文字数）
- **导入方式**：管理端「批量导入」，文件名后缀必须是 `.jsonl`
- **导入端点**：`POST /api/knowledge/documents/import`（Spring context-path 为 `/api`）
- **source_type**：统一 `UPLOAD`（人工整理，非 AI 合成、非权威发布物）

---

## 一、为什么是「一个方向一篇」而不是一个大 JSONL 多篇

`DocumentParser` 的 JSONL 分支逻辑（已实测确认）是：

```java
byTitle.computeIfAbsent(title, k -> new StringBuilder());   // 同一 doc_title 累积到同一个 StringBuilder
sb.append(section).append('\n');                            // section 独立成行 → 供 TextSplitter 识别章节
sb.append(body).append("\n\n");
```

即 **`doc_title` 就是文档边界**。所以「粒度」这件事完全由 `doc_title` 怎么取决定：

| 粒度方案 | 管理端会看到 | 引用卡片显示 | 取舍 |
|---|---|---|---|
| 全库一篇（`doc_title` 全同） | 1 篇 | 《心理健康知识大全》 | 列表最干净，但单篇几十万字、重建索引极慢，溯源只能到章节 |
| **每方向一篇（本方案）** | 10 篇 | 《PHQ-9 患者健康问卷条目说明》 | **标题有意义、重建粒度可控、删一篇不伤其余** |
| 每小节一篇 | 80+ 篇 | 《1.3 第 1、2 题问的是什么》 | 溯源最精准，但管理端列表冗长，且过细的片段检索时缺少上下文 |

选中间档的核心理由：**引用卡片展示的是 `doc_title`**。用户看到「参考资料 · PHQ-9 患者健康问卷条目说明」
就知道依据是什么；看到「参考资料 · 心理健康知识（一）」等于没信息。

---

## 二、分类结构（10 篇）

```
心理健康与医疗知识库
├── 一、心理评测工具
│   ├── ① PHQ-9 患者健康问卷条目与解读        （抑郁筛查，9 条目）
│   ├── ② GAD-7 广泛性焦虑量表条目与解读      （焦虑筛查，7 条目）
│   └── ③ 常用心理测评量表使用说明            （PSS-10 / PSQI / 自评通用规则）
├── 二、常见心理症状
│   └── ④ 常见心理症状科普                    （情绪低落 / 焦虑 / 睡眠 / 躯体化 / 成瘾）
├── 三、风险与干预
│   └── ⑤ 危机识别与干预指引                  （自杀风险信号 / 求助途径 / 陪伴原则）
├── 四、医疗与用药
│   ├── ⑥ 常见医疗常识与就医指引              （六大系统常见症状 / 挂号分诊 / 检查流程）
│   └── ⑦ 精神科药物常识与用药安全            （SSRI/SNRI / 停药反应 / 服药注意）
├── 五、服务衔接
│   └── ⑧ 就诊预约与转介指引                  （挂号渠道 / 医保 / 复诊 / 转诊流程）
├── 六、人群与生活
│   ├── ⑨ 特殊人群心理健康                    （青少年 / 老年 / 孕产期 / 慢病共病）
│   └── ⑩ 生活方式与心理调适                  （睡眠卫生 / 运动 / 饮食 / 正念放松）
└── （附）医患沟通与康复随访 → 并入 ⑧ 与 ⑦ 的相应章节，不单独成篇
```

### 各篇定位与覆盖要点

| # | doc_title | 定位 | 覆盖要点 |
|---|---|---|---|
| ① | PHQ-9 患者健康问卷条目与解读 | 抑郁自评工具 | 9 个条目逐条含义、四档作答、总分分级、单条高危 |
| ② | GAD-7 广泛性焦虑量表条目与解读 | 焦虑自评工具 | 7 个条目、与「正常担心」的区别、躯体症状 |
| ③ | 常用心理测评量表使用说明 | 其他量表与通用规则 | PSS-10 压力、PSQI 睡眠、自评注意事项、结果解读边界 |
| ④ | 常见心理症状科普 | 症状识别 | 情绪低落、焦虑躯体化、失眠、疑病、酒精依赖 |
| ⑤ | 危机识别与干预指引 | 风险应对 | 危险信号、求助渠道、如何陪伴、常见误区 |
| ⑥ | 常见医疗常识与就医指引 | 综合医疗常识 | 发热/头痛/胸闷/腹痛/眩晕/乏力、该挂什么科、急诊指征 |
| ⑦ | 精神科药物常识与用药安全 | 用药安全 | 起效时间、副作用应对、停药反应、忌自行调药 |
| ⑧ | 就诊预约与转介指引 | 服务流程 | 挂号渠道、首诊准备、医保报销、转诊与会诊 |
| ⑨ | 特殊人群心理健康 | 人群差异 | 青少年、老年、孕产期、慢病患者的心理特点 |
| ⑩ | 生活方式与心理调适 | 自助方法 | 睡眠卫生、运动处方、饮食、呼吸放松、社会支持 |

---

## 三、JSONL 字段规范（严格对齐 `DocumentParser`）

每行一个 JSON 对象，字段如下——**字段名是代码里 `str(obj, "...")` 的字面量，不可改**：

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `doc_title` | ✅ | string | 文档标题，**同一标题的行会合并成一篇**。也是前端引用卡片展示的文字 |
| `content` | ✅ | string | 正文段落。建议 400–600 字，太长会被 `TextSplitter` 再切 |
| `section` | ⬜ | string | 章节路径，**独立成一行**再拼正文。供章节识别与引用溯源 |
| `source_type` | ⬜ | string | `UPLOAD` / `AUTHORITATIVE` / `SYNTHETIC`。本语料用 `UPLOAD`；留空默认 `UPLOAD` |
| `publish_year` | ⬜ | **number** | 发布年份，用于医疗时效性加权。**必须写成数字不加引号**，否则静默丢弃 |

### section 命名规则

沿用项目既有风格（与 `corpus/synthetic_corpus.jsonl` 一致）：

```
第 <章号> 节 <章标题> / <章号>.<小节号> <小节标题>
```

实例：

```json
{"doc_title": "PHQ-9 患者健康问卷条目与解读",
 "source_type": "UPLOAD",
 "publish_year": 2024,
 "section": "第 1 节 PHQ-9 概览 / 1.1 用途与适用人群",
 "content": "……"}
```

**为什么 section 要带「第 N 节」前缀**：`TextSplitter` 把 section 当作章节锚点，
同一「第 N 节」下的多个小节会归到同一个父块，父块标题即章节名。
不带前缀的话每个小节各自成父块，喂给模型的上下文会碎。

---

## 四、导入步骤

### 方式 A：管理端界面（推荐）

1. 用管理员账号登录（`admin / admin123`）
2. 进入「知识库管理」→「批量导入」
3. 上传 `psycare_corpus.jsonl`，文件名后缀务必是 `.jsonl`
4. 切分参数留空走默认（子块 400 / 父块 1400 / 重叠 50）
5. 等状态从 `PENDING/INDEXING` 变为 `READY`

### 方式 B：接口直传

```bash
curl --noproxy '*' -X POST http://localhost:18989/api/knowledge/documents/import \
  -H 'Content-Type: application/json' \
  -b cookies.txt \
  -d "$(python -c "
import json
c = open('psycare-server/corpus/psycare_corpus.jsonl', encoding='utf-8').read()
print(json.dumps({'fileName': 'psycare_corpus.jsonl', 'content': c}, ensure_ascii=False))
")"
```

### 导入前建议先预览（零成本、不调模型）

```bash
curl --noproxy '*' -X POST http://localhost:18989/api/knowledge/preview \
  -H 'Content-Type: application/json' -b cookies.txt \
  -d '{"fileName":"psycare_corpus.jsonl","content":"<同上>"}'
```

`preview` 与 `import` 走**同一个 `DocumentParser.parse`**，所以「预览看到的」就是「导进去的样子」。

---

## 五、生成时做过的校验（可复跑）

```bash
python psycare-server/corpus/verify_corpus.py
```

校验项：

1. 每行是合法 JSON 对象
2. `doc_title` / `content` 均非空
3. `publish_year` 是数字（不是字符串）
4. **零爬虫/编程关键词**（`requests` / `BeautifulSoup` / `lxml` / `Selenium` / `xpath` /
   `爬虫` / `反爬` / `User-Agent` / `cookie` / 代理池 等 20 项）
5. 输出篇数、每篇行数、总字数统计

---

## 相关文档

- `硬约定.md` #90 —— 「改语料必须同步改评测集」（删除爬虫文档后 `queries_in.jsonl` 必须重建）
- `开发文档.md` 第十八章 —— Phase 4 在线检索与引用
- `部署说明.md` —— `psycare.retrieval.*` 配置与阈值校准

---

## 六、爬虫内容的清理（2026-10-09 实测状态）

### 6.1 事实先对齐

**爬虫内容从来不在磁盘语料里，只在数据库里。**

实测核查结果：

| 位置 | 状态 |
|---|---|
| `corpus/synthetic_corpus.jsonl`（81 行） | **0 行**含爬虫关键词 —— 它从未导入过库，只是磁盘上的生成物 |
| `kb_document` | 只有一行 `id=1《爬虫知识》`，**已 `is_deleted=1`**（逻辑删除） |
| `kb_chunk` | **物理 0 行**（分块是物理删的） |
| Milvus `psycare_kb_chunk` | **0 个向量** |

也就是说：**《爬虫知识》已经被删掉了，只差 `kb_document` 里那行逻辑删除记录的物理清尾。**

### 6.2 删除路径的实现（不是 bug）

`KnowledgeIndexService.delete()` 的行为是刻意设计的**不对称删除**：

```java
txTemplate.execute(s -> {
    // 分块用物理删：DDL 注释写明「分块不可变，内容变了就是一次重建」，
    // 留在库里只会让每次重建都多积一批 is_deleted=1 的行，且没有任何查询会用到它们。
    chunkMapper.hardDeleteByDoc(docId);
    documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
            .eq(KbDocument::getId, docId)
            .set(KbDocument::getIsDeleted, 1)      // 文档只做逻辑删（保留可追溯）
            .set(KbDocument::getDeletedAt, LocalDateTime.now())
            .set(KbDocument::getUpdatedAt, LocalDateTime.now()));
    return null;
});
```

**为什么这样设计**：分块是「不可变的事实」，删了就是不要了；文档行保留逻辑删除，是为了留下「这篇文档曾经存在过、什么时候删的」这条痕迹。逻辑删除的行会被 MyBatis-Plus 自动过滤（`is_deleted=0`），**不影响任何查询，也不影响新导入**。

### 6.3 清理步骤

**第一步（可选）：物理清掉逻辑删除的残留行**

如果在意表里的残留（比如想让 `id` 从 1 重新开始），执行：

```sql
-- 先看要删什么
SELECT id, title, status, is_deleted, deleted_at FROM kb_document WHERE is_deleted = 1;

-- 物理删（已经没有分块了，这一步是清文档行）
DELETE FROM kb_document WHERE is_deleted = 1;

-- 核对
SELECT COUNT(*) FROM kb_document;   -- 应为 0
```

> 也可以直接跑 `.workbuddy/scripts/kb_cleanup_check.py --sql` 拿到这段 SQL 与前置核对语句。

**如果你不打算清**：直接导入新语料即可，两不相干。

**第二步：确认 Milvus 侧没有孤儿向量**

```bash
python .workbuddy/scripts/kb_cleanup_check.py
```

看第 3 节「两侧一致性」。若报「仅 Milvus 有（孤儿向量）」，说明删文档时 Milvus 调用失败过（`delete()` 里失败只 `log.warn` 不抛错）。处理方式：**在管理端对相关文档点「重建」**，不需要手工操作 Milvus。

**第三步：导入本批新语料**（见 §4 导入步骤）

### 6.4 导入后的验收清单

1. 管理端知识库列表应显示 **10 篇**，状态均为 `READY`
2. 列表里**不应再有《爬虫知识》**（连逻辑删除的也不该显示——列表查询带 `is_deleted=0`）
3. 跑一致性核对：`python .workbuddy/scripts/kb_cleanup_check.py` → 第 3 节应报「✓ 一致」
4. 跑检索冒烟（管理端「检索试跑」或接口），确认这两句都能召回心理/医疗内容：
   - `PHQ-9 第 9 题为什么不能只看总分？` → 应召回《PHQ-9 患者健康问卷条目与解读》
   - `抗抑郁药吃多久才会有效果？` → 应召回《精神科药物常识与用药安全》
5. 确认这两句**检索为空**（库外拒答）：
   - `红烧肉的正宗做法是什么？` → 应返回 0 条
   - `世界杯上一届冠军是哪个队？` → 应返回 0 条
6. 跑一次完整评测：`python .workbuddy/scripts/eval_retrieval.py`
   - 实测目标：Recall@5 ≥ 0.85 / MRR ≥ 0.7 / 拒答准确率 ≥ 0.9
   - 若 Recall 偏低，用 `--grid threshold` 扫阈值找分离点，然后改 `psycare.retrieval.min-dense-score`

### 6.5 一条必须记住的约束

**换了语料必须同步换评测集**（硬约定 #90）。

本批语料导入后，旧的 `queries_in.jsonl`（24 条，全部基于爬虫内容）**已经作废**，本次已重建为：

| 文件 | 条数 | 内容 |
|---|---|---|
| `queries_in.jsonl` | 43 | 基于本批语料，覆盖 10 篇 |
| `queries_out.jsonl` | 10 | 库外（烹饪/体育/园艺/物理/历史/乐器/税务/汽车/编程/航空） |

两者由 `corpus/gen_eval_queries.py` **从语料反向派生**，字段校验会确保每条 query 的 `expect_sections` / `expect_keywords` 都能在语料里查到，避免「语料改了、评测集还指着旧内容」的静默失效。

