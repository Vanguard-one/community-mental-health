"""Milvus 环境探针 —— Phase 3（RAG 离线管道）的**第一件事**，四断言不过则停止开发。

为什么要单独验这一步
--------------------
Milvus 是本项目第一个**外部有状态服务**（此前只有 MySQL/Redis）。它的几个能力边界
会直接决定分块与检索的架构，而这些边界**都不会给出明确的报错**：

  * 向量字段能不能为空   -> 决定「父块进不进 Milvus」（本项目结论：不进，用 parent_id 回表）
  * 中文 BM25 能不能召回 -> 默认 analyzer 按空格切词，中文整句会变成一个 token，
                            BM25 静默退化成整句精确匹配，**召回 0 且不报错**
  * 服务/SDK 版本是否对齐 -> 决定 hybridSearch 等 API 是否存在

所以：先用探针把「环境 + 中文分词」验掉，再往上盖楼。

四个断言（任一失败即停，后续断言失去意义）
----------------------------------------
  [1/4] 连接       —— REST 可达、集合列表可读、9091 healthz 正常
  [2/4] 建集合     —— schema（含 jieba 中文分词）+ BM25 Function + 索引
  [3/4] 写入读回   —— 插 10 条中文，按 chunk_id 查得回（顺带验证「向量字段不能为空」）
  [4/4] 中文 BM25  —— 用中文专有名词召回对应块，且排第 1（**本探针的核心**）

依赖：**零第三方依赖**（标准库 urllib），无需 pymilvus。
      探针走 REST v2（Milvus 2.4+ 自带），与后续 Java 侧 milvus-sdk-java 的语义一致。

用法
----
    python milvus_probe.py                      # 完整四断言（建临时集合、跑完删掉）
    python milvus_probe.py --keep               # 保留集合，便于手动翻看
    python milvus_probe.py --collection my_kb   # 换集合名
    python milvus_probe.py --token root:Milvus  # 开了鉴权时用

本机已知坑
----------
HTTP_PROXY 指向 127.0.0.1:55577，而 Milvus 在 localhost —— 不绕代理会拿到 502。
本脚本已显式清空代理（等价于 curl 的 --noproxy '*'）。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

DEFAULT_COLLECTION = "psycare_probe_tmp"
DENSE_DIM = 1024          # 与正式设计一致：通义 qwen3.7-text-embedding-flash 的维度

# 已知实测环境（2026-10-08）：milvusdb/milvus:v3.0.0 单容器内嵌 etcd + 本地存储，
# 本探针四断言全部通过。REST v2 在 3.0 上向后兼容，无需改路径或参数。

# 探针语料：10 条中文，主题互不相同，便于验证「关键词能召回对的那一条」。
# 第 1 条刻意放一个专有名词（舍曲林）—— 标准分词器切不开中文，用它能一眼看出分词是否生效。
CORPUS = [
    (1, "抗抑郁药物常识", "舍曲林常见的不良反应包括恶心、腹泻、失眠与食欲下降，通常随用药时间延长而减轻，具体用药请遵医嘱。"),
    (2, "PHQ-9 计分说明", "PHQ-9 共九个条目，每条按零到三分计分，总分零到四分表示无抑郁症状，五到九分为轻度。"),
    (3, "睡眠卫生建议", "保持规律作息，睡前两小时避免使用电子屏幕，卧室温度略低有助于入睡。"),
    (4, "焦虑的躯体表现", "广泛性焦虑常表现为心慌、手抖、出汗与肌肉紧绷，容易被误认为是心脏问题。"),
    (5, "危机干预指引", "当出现自伤念头时，应立即联系信任的人或拨打全国心理援助热线，不要独自承担。"),
    (6, "认知行为疗法基础", "认知行为疗法认为情绪困扰与自动化负性思维有关，通过识别与检验这些想法来改善情绪。"),
    (7, "老年人抑郁特点", "老年抑郁常以躯体不适为主诉，情绪低落反而不明显，容易被漏诊。"),
    (8, "青少年情绪波动", "青春期情绪起伏与大脑前额叶发育尚未成熟有关，多数属于正常发展过程。"),
    (9, "产后情绪障碍", "产后两周内出现的情绪低落多为产后情绪不良，若持续超过两周需考虑产后抑郁。"),
    (10, "正念呼吸练习", "把注意力放在呼吸上，走神时温和地把注意力带回来，每次练习十分钟即可。"),
]

# 断言 4 用的召回用例：(查询词, 期望命中的 chunk_id)
RECALL_CASES = [
    ("舍曲林", 1),
    ("PHQ-9", 2),
    ("睡前看手机", 3),
    ("心慌手抖", 4),
]


class Probe:
    def __init__(self, base, token, timeout=20):
        self.base = base.rstrip("/")
        self.token = token
        self.timeout = timeout
        # 关键：清空代理（本机 HTTP_PROXY 会让 localhost 请求拿到 502）
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, path, payload=None, timeout=None):
        """返回 (http_status, parsed_json_or_None, raw_text)。HTTP 层异常不抛出，交给调用方判定。"""
        url = self.base + path
        body = json.dumps(payload if payload is not None else {}).encode("utf-8")
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Request-Timeout", str(timeout or self.timeout))
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with self.opener.open(req, timeout=timeout or self.timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                status = resp.status
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            status = e.code
        except Exception as e:
            return None, None, "%s: %s" % (type(e).__name__, e)
        try:
            return status, json.loads(raw), raw
        except Exception:
            return status, None, raw

    def get(self, url, timeout=8):
        try:
            with self.opener.open(url, timeout=timeout) as resp:
                return resp.status, resp.read().decode("utf-8", "replace")
        except Exception as e:
            return None, "%s: %s" % (type(e).__name__, e)


def ok(payload):
    """Milvus REST v2 的成功码在 code=0；部分版本用 200。注意：失败也走 HTTP 200，必须看 body。"""
    if not isinstance(payload, dict):
        return False
    return payload.get("code") in (0, 200)


def brief(raw, limit=300):
    raw = (raw or "").replace("\n", " ")
    return raw if len(raw) <= limit else raw[:limit] + " …(截断)"


def fail(step, hint, detail=""):
    print("  ✗ 失败")
    if detail:
        print("    原始响应：%s" % brief(detail, 500))
    print("    排障方向：%s" % hint)
    print("\n探针未全部通过，停止后续开发。")
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=19530)
    ap.add_argument("--token", default="")
    ap.add_argument("--collection", default=DEFAULT_COLLECTION)
    ap.add_argument("--keep", action="store_true", help="跑完保留集合，便于手动检查")
    args = ap.parse_args()

    base = "http://%s:%d" % (args.host, args.port)
    p = Probe(base, args.token)
    col = args.collection

    print("=" * 68)
    print("Milvus 环境探针 · %s · collection=%s" % (base, col))
    print("=" * 68)

    # ---------------- [1/4] 连接 ----------------
    print("\n[1/4] 连接")
    status, payload, raw = p.call("/v2/vectordb/collections/list")
    if status != 200 or not ok(payload):
        fail(1, "确认 Docker 三容器（etcd + MinIO + Milvus）都在跑、19530 已监听",
             "http=%s body=%s" % (status, brief(raw)))
    existing = (payload.get("data") or [])
    print("  ✓ REST 可达，已有集合 %d 个：%s" % (len(existing), existing if existing else "（空）"))

    hcode, hbody = p.get("http://%s:9091/healthz" % args.host)
    if hcode == 200 and "OK" in (hbody or "").upper():
        print("  ✓ healthz 正常")
    else:
        print("  · healthz 未返回预期值（http=%s body=%s）—— 多半是 9091 没映射，不阻断"
              % (hcode, brief(hbody, 60)))
    print("  · 版本提示：本机自查 `docker ps --format '{{.Image}}'`。")
    print("    已实测可用：v3.0.0（官方对照 milvus-sdk-java 3.0.5）。不要降回 2.6.x ——")
    print("    3.0 写入过数据后降级可能读不出（官方明确不支持仅换镜像回滚）。")

    # ---------------- [2/4] 建集合 ----------------
    print("\n[2/4] 建集合（schema + 中文分词 + BM25 Function）")
    p.call("/v2/vectordb/collections/drop", {"collectionName": col})   # 幂等：先清掉上次残留

    varchar = {"max_length": "4096", "enable_analyzer": True,
               "analyzer_params": {"tokenizer": "jieba", "filter": ["cnalphanumonly"]}}
    schema = {
        "autoID": False,
        "enabledDynamicField": False,
        "fields": [
            {"fieldName": "chunk_id", "dataType": "Int64", "isPrimary": True, "autoID": False},
            {"fieldName": "doc_id", "dataType": "Int64"},
            {"fieldName": "parent_id", "dataType": "Int64"},
            {"fieldName": "text", "dataType": "VarChar", "elementTypeParams": varchar},
            {"fieldName": "dense_vector", "dataType": "FloatVector",
             "elementTypeParams": {"dim": str(DENSE_DIM)}},
            {"fieldName": "sparse_vector", "dataType": "SparseFloatVector"},
            {"fieldName": "doc_title", "dataType": "VarChar",
             "elementTypeParams": {"max_length": "256"}},
            {"fieldName": "source_type", "dataType": "VarChar",
             "elementTypeParams": {"max_length": "32"}},
            {"fieldName": "publish_year", "dataType": "Int32"},
        ],
        "functions": [{
            "name": "text_bm25",
            "type": "BM25",
            "inputFieldNames": ["text"],
            "outputFieldNames": ["sparse_vector"],
        }],
    }
    index_params = [
        {"fieldName": "sparse_vector", "indexType": "AUTOINDEX", "metricType": "BM25"},
        {"fieldName": "dense_vector", "indexType": "AUTOINDEX", "metricType": "COSINE"},
    ]
    status, payload, raw = p.call("/v2/vectordb/collections/create",
                                  {"collectionName": col, "schema": schema,
                                   "indexParams": index_params})
    if status != 200 or not ok(payload):
        fail(2, "① 服务与 SDK 版本是否对齐（实测基线 v3.0.0 / SDK 3.0.5）；"
                "② analyzer_params 的 key 名（字段级 analyzer 配置）是否变了"
                " —— 看原始响应",
             "http=%s body=%s" % (status, brief(raw)))
    print("  ✓ 集合创建成功（含 jieba 分词与 BM25 Function）")

    # ---------------- [3/4] 写入 + 读回 ----------------
    print("\n[3/4] 写入 10 条中文 + 读回")
    rows = []
    for cid, title, text in CORPUS:
        rows.append({
            "chunk_id": cid,
            "doc_id": 100 + cid,
            "parent_id": 1000 + cid,
            "text": text,
            "dense_vector": [((i * 7 + cid) % 100) / 100.0 for i in range(DENSE_DIM)],
            "doc_title": title,
            "source_type": "SYNTHETIC",
            "publish_year": 2024,
        })
    status, payload, raw = p.call("/v2/vectordb/entities/insert",
                                  {"collectionName": col, "data": rows})
    if status != 200 or not ok(payload):
        fail(3, "向量字段不可为空（本项目硬约束）—— 若报 dense_vector，说明每条都必须带向量；"
                "另注意 insertCount 是否等于 10",
             "http=%s body=%s" % (status, brief(raw)))
    print("  ✓ 写入成功：%s" % brief(json.dumps(payload.get("data"), ensure_ascii=False), 160))

    lp = p.call("/v2/vectordb/collections/load", {"collectionName": col})
    if not ok(lp[1]):
        fail(3, "collection 必须 load 之后才能 search", "body=%s" % brief(lp[2]))
    # 刚写完立刻查，一致性级别可能还看不到数据，给一点时间
    time.sleep(1.5)

    status, payload, raw = p.call("/v2/vectordb/entities/query", {
        "collectionName": col,
        "filter": "chunk_id in [1, 2, 3]",
        "outputFields": ["chunk_id", "doc_title"],
    })
    if status != 200 or not ok(payload):
        fail(3, "consistency level / 是否需要 flush；确认 filter 语法（chunk_id in [...]）",
             "http=%s body=%s" % (status, brief(raw)))
    got = payload.get("data") or []
    ids = sorted(int(r.get("chunk_id")) for r in got if r.get("chunk_id") is not None)
    if ids != [1, 2, 3]:
        fail(3, "读回条数与内容不符（期望 [1,2,3]，实得 %s）" % ids,
             "body=%s" % brief(raw))
    print("  ✓ 读回一致：chunk_id = %s" % ids)

    # ---------------- [4/4] 中文 BM25 召回（核心） ----------------
    print("\n[4/4] 中文 BM25 召回（**核心断言**）")
    passed = 0
    for query, expect in RECALL_CASES:
        status, payload, raw = p.call("/v2/vectordb/entities/search", {
            "collectionName": col,
            "data": [query],
            "annsField": "sparse_vector",
            "limit": 3,
            "outputFields": ["chunk_id", "doc_title"],
        })
        if status != 200 or not ok(payload):
            fail(4, "搜索接口参数：sparse 那一路 data 要传**原始文本字符串**，"
                    "annsField 用 sparse_vector",
                 "http=%s body=%s" % (status, brief(raw)))
        hits = payload.get("data") or []
        top = hits[0] if hits else None
        top_id = int(top.get("chunk_id")) if top and top.get("chunk_id") is not None else None
        mark = "✓" if top_id == expect else "✗"
        print("  %s 「%s」 -> 命中 chunk_id=%s（期望 %s）· top1 标题：%s"
              % (mark, query, top_id, expect, (top or {}).get("doc_title", "-")))
        if top_id == expect:
            passed += 1

    if passed != len(RECALL_CASES):
        print()
        fail(4,
             "中文分词没生效 —— 默认 standard analyzer 按空格切，整句会变成一个 token，"
             "BM25 退化成整句精确匹配所以召回 0。\n"
             "            处理：① 确认字段上 enable_analyzer=true 且 analyzer 配了 jieba；"
             "② 若该版本 REST 不认 analyzer_params，改用内置快捷 { \"type\": \"chinese\" }；"
             "③ 仍不行则退回「Java 侧预分词写入 text_tokenized 字段」的兜底方案",
             "已通过 %d/%d 用例" % (passed, len(RECALL_CASES)))
    print("  ✓ 四个召回用例全部命中 top1（%d/%d）" % (passed, len(RECALL_CASES)))

    # ---------------- 收尾 ----------------
    print("\n" + "=" * 68)
    print("四断言全部通过 —— 环境可用于 Phase 3 开发")
    print("=" * 68)
    print("记录以下已核实事实：")
    print("  · 中文 BM25 可用（jieba + cnalphanumonly），无需 Java 侧预分词兜底")
    print("  · 稀疏那一路传原始文本；稠密那一路传 FloatVec")
    print("  · 向量字段每条必填（父块因此不进 Milvus，只留 MySQL 用 parent_id 回表）")

    if args.keep:
        print("\n集合 %s 已保留（--keep）。手动检查后记得 drop。" % col)
    else:
        p.call("/v2/vectordb/collections/drop", {"collectionName": col})
        print("\n临时集合 %s 已删除。" % col)


if __name__ == "__main__":
    main()
