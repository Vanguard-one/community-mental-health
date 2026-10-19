#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
RAG 检索质量评测（Phase 4 的量化验收工具）。

三件事：
  ① 跑评测集，算 Recall@5 / MRR / 拒答准确率；
  ② 支持参数网格（块大小 / overlap / 阈值），**每次只动一个参数**；
  ③ 输出分离点分析，用来定 min-dense-score。

为什么必须走 HTTP 而不是直接查 Milvus：
  评测的对象是「线上真实走的那条路」—— 含阈值过滤、父块回表、预算裁剪。
  直连 Milvus 只能测到召回层，测不到「过滤掉了不该过滤的」这类真正的线上问题。

零第三方依赖（隔离环境无 requests）：标准库 urllib。
本机代理会拦 127.0.0.1，必须显式 ProxyHandler({})。

用法：
  python eval_retrieval.py                      # 跑默认评测集
  python eval_retrieval.py --min-score 0.6      # 单参数覆盖
  python eval_retrieval.py --grid threshold     # 阈值网格扫描
  python eval_retrieval.py --queries my.jsonl   # 自定义评测集
  python eval_retrieval.py --dry-run            # 只校验评测集格式，不请求
"""

import argparse
import json
import os
import re
import statistics
import sys
import urllib.error
import urllib.request
from collections import Counter

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_IN = os.path.join(BASE_DIR, "psycare-server", "corpus", "queries_in.jsonl")
DEFAULT_OUT = os.path.join(BASE_DIR, "psycare-server", "corpus", "queries_out.jsonl")

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------- 配置与登录

def read_backend():
    """从 application.yml 读端口与 context-path（不引 yaml 库，正则足够）。"""
    p = os.path.join(BASE_DIR, "psycare-server", "src", "main", "resources", "application.yml")
    cfg = open(p, encoding="utf-8").read()
    port = re.search(r"^server:\s*\n(?:.*\n)*?\s+port:\s*(\d+)", cfg, re.M)
    ctx = re.search(r"context-path:\s*(\S+)", cfg)
    return (
        int(port.group(1)) if port else 18989,
        (ctx.group(1) if ctx else "/api").rstrip("/"),
    )


def read_llm_key():
    p = os.path.join(BASE_DIR, "psycare-server", "src", "main", "resources", "application.yml")
    cfg = open(p, encoding="utf-8").read()
    m = re.search(r"llm:\s*\n(?:.*\n)*?\s+api-key:\s*(\S+)", cfg)
    return m.group(1) if m else os.environ.get("LLM_API_KEY", "")


def login(base, username, password):
    body = json.dumps({"userName": username, "password": password}).encode()
    req = urllib.request.Request(
        base + "/auth/login", data=body,
        headers={"Content-Type": "application/json"})
    with OPENER.open(req, timeout=15) as r:
        d = json.loads(r.read().decode("utf-8"))
    if d.get("code") != 200:
        raise SystemExit("登录失败：%s" % d.get("msg"))
    return (d.get("data") or {}).get("token")


# ---------------------------------------------------------------- 检索调用

def call_retrieve(base, token, query, min_score=None):
    """调后端检索接口（评测用，管理员权限）。返回命中的 chunk 列表。"""
    body = {"query": query}
    if min_score is not None:
        body["minDenseScore"] = min_score
    req = urllib.request.Request(
        base + "/knowledge/retrieve", data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        with OPENER.open(req, timeout=30) as r:
            d = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise SystemExit("检索接口 HTTP %s：%s" % (e.code, e.read().decode()[:200]))
    if d.get("code") != 200:
        raise SystemExit("检索接口失败：%s" % d.get("msg"))
    # 端点是诊断接口，data 是一个对象 {query, minDenseScore, hitCount, costMs, hits}
    data = d.get("data") or {}
    if isinstance(data, dict):
        return data.get("hits") or []
    return data or []          # 兼容将来直接把 hits 放在 data 里的写法


# ---------------------------------------------------------------- 指标

def load_jsonl(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except Exception as e:
                raise SystemExit("%s 第 %d 行不是合法 JSON：%s" % (path, i, e))
    return rows


def hit_is_relevant(h, expect_sections, expect_keywords):
    """
    判定一条命中是否「相关」。**双口径，满足其一即可**：

      ① section 命中 —— 文档有规范层级（Markdown 标题 / JSONL 语料）时最精确；
      ② content 含任一关键词 —— 纯文本粘贴的文档 section 全是空串，
         此时只能看正文。这不是退而求其次，而是必须：
         用户从 Word 里复制一段话粘进来，本来就拿不到任何结构信息。

    只做「包含」判断，不做分词/相似度 —— 评测工具本身要零依赖、结果可复现，
    判定逻辑越简单越好（复杂的判定会把评测工具的 bug 混进指标里）。
    """
    sec = h.get("section") or ""
    for exp in expect_sections:
        if exp and exp in sec:
            return True
    if expect_keywords:
        body = h.get("content") or ""
        for kw in expect_keywords:
            if kw and kw in body:
                return True
    return False


def recall_at_k(hits, expect_sections, expect_keywords, k=5):
    """命中的前 k 条里，是否覆盖了期望章节 / 关键词（有一个就算命中）。"""
    for h in hits[:k]:
        if hit_is_relevant(h, expect_sections, expect_keywords):
            return 1.0
    return 0.0


def reciprocal_rank(hits, expect_sections, expect_keywords):
    """第一个正确命中的排名的倒数。"""
    for i, h in enumerate(hits, 1):
        if hit_is_relevant(h, expect_sections, expect_keywords):
            return 1.0 / i
    return 0.0


def evaluate(base, token, in_rows, out_rows, min_score=None, k=5):
    details = []
    for row in in_rows:
        hits = call_retrieve(base, token, row["query"], min_score)
        exp = row.get("expect_sections") or []
        kws = row.get("expect_keywords") or []
        details.append({
            "query": row["query"],
            "n": len(hits),
            "recall": recall_at_k(hits, exp, kws, k),
            "rr": reciprocal_rank(hits, exp, kws),
            "top": [(h.get("docTitle"), h.get("section"), h.get("denseScore")) for h in hits[:3]],
        })
    recall = statistics.mean([d["recall"] for d in details]) if details else 0.0
    mrr = statistics.mean([d["rr"] for d in details]) if details else 0.0

    refused, leaked = 0, []
    for row in out_rows:
        hits = call_retrieve(base, token, row["query"], min_score)
        if not hits:
            refused += 1
        else:
            leaked.append((row["query"], [(h.get("docTitle"), h.get("denseScore")) for h in hits[:2]]))
    refusal_acc = (refused / len(out_rows)) if out_rows else 1.0

    return {
        "k": k,
        "recall": recall,
        "mrr": mrr,
        "refusal_acc": refusal_acc,
        "n_in": len(in_rows),
        "n_out": len(out_rows),
        "details": details,
        "leaked": leaked,
    }


def report(name, res, targets=None):
    print("\n" + "=" * 62)
    print("  %s" % name)
    print("=" * 62)
    print("  库内问题 %d 条 · 库外问题 %d 条" % (res["n_in"], res["n_out"]))
    print("  %-16s %8.4f" % ("Recall@%d" % res["k"], res["recall"]))
    print("  %-16s %8.4f" % ("MRR", res["mrr"]))
    print("  %-16s %8.4f" % ("拒答准确率", res["refusal_acc"]))
    if targets:
        print("  " + "-" * 58)
        for label, got, want in targets:
            mark = "✓" if got >= want else "✗"
            print("  %s %-14s %.4f  (目标 ≥ %.2f)" % (mark, label, got, want))
    misses = [d for d in res["details"] if d["recall"] == 0]
    if misses:
        print("\n  ── 未命中 %d 条 ──" % len(misses))
        for d in misses[:8]:
            print("    ✗ %s" % d["query"][:44])
            for t in d["top"][:2]:
                print("        top: %s / %s (%.3f)" % (
                    (t[0] or "")[:14], (t[1] or "无章节")[:26],
                    t[2] if t[2] is not None else float("nan")))
    if res["leaked"]:
        print("\n  ── 库外问题未被拒答 %d 条 ──" % len(res["leaked"]))
        for q, top in res["leaked"][:6]:
            print("    ⚠ %s  →  %s" % (q[:40], top))
    return res["recall"], res["mrr"], res["refusal_acc"]


# ---------------------------------------------------------------- 入口

def main():
    ap = argparse.ArgumentParser(description="RAG 检索质量评测")
    ap.add_argument("--queries", default=DEFAULT_IN, help="库内评测集（jsonl）")
    ap.add_argument("--out-queries", default=DEFAULT_OUT, help="库外评测集（jsonl）")
    ap.add_argument("--min-score", type=float, default=None, help="覆盖 min-dense-score")
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument("--grid", choices=["threshold", "none"], default="none",
                    help="参数网格扫描")
    ap.add_argument("--dry-run", action="store_true", help="只校验评测集，不请求后端")
    args = ap.parse_args()

    in_rows = load_jsonl(args.queries)
    out_rows = load_jsonl(args.out_queries) if os.path.exists(args.out_queries) else []
    print("评测集：库内 %d 条 · 库外 %d 条" % (len(in_rows), len(out_rows)))

    if args.dry_run:
        # 一条 query 至少要有一个判定依据，否则它参与评测但永远判 0，
        # 会静默拉低指标 —— 这种「评测集自身的错」比被测代码的错更难发现。
        bad = [r for r in in_rows
               if not r.get("query")
               or (not r.get("expect_sections") and not r.get("expect_keywords"))]
        print("格式检查：%s" % ("全部合法 ✓" if not bad else "有 %d 条缺 query 或判定依据 ✗" % len(bad)))
        for r in bad[:10]:
            print("    ✗ %s" % (r.get("query") or "(无 query)")[:50])
        # 库内/库外问题不该有交集，否则同一条既要求召回又要求拒答
        in_q = {r.get("query") for r in in_rows}
        dup = [r.get("query") for r in out_rows if r.get("query") in in_q]
        if dup:
            print("库内/库外集重复 %d 条 ✗：%s" % (len(dup), dup[:3]))
            return 2
        return 0 if not bad else 2

    if not in_rows:
        print("库内评测集为空，无法评测", file=sys.stderr)
        return 2

    port, ctx = read_backend()
    base = "http://127.0.0.1:%d%s" % (port, ctx)
    print("后端：%s" % base)
    token = login(base, "admin", "admin123")

    if args.grid == "threshold":
        print("\n阈值网格扫描（每次只动这一个参数）")
        print("%-10s %-12s %-10s %s" % ("阈值", "Recall@%d" % args.k, "MRR", "拒答率"))
        for th in [0.3, 0.4, 0.5, 0.6, 0.7]:
            r = evaluate(base, token, in_rows, out_rows, th, args.k)
            print("%-10.2f %-12.4f %-10.4f %.4f" % (th, r["recall"], r["mrr"], r["refusal_acc"]))
        print("\n选择原则：优先满足 Recall@%d ≥ 0.85，其次看拒答率与 MRR。" % args.k
              + "\n**不要**同时动块大小与阈值 —— 那说不清是哪个起了作用。")
        return 0

    res = evaluate(base, token, in_rows, out_rows, args.min_score, args.k)
    # 单跑时回显真实生效阈值：没传 --min-score 就是配置值（0.5），
    # 打印成「配置值」而不是猜一个数字，避免报告与实际不符
    score = args.min_score if args.min_score is not None else "配置值(默认 0.5)"
    report("检索质量（min-dense-score = %s）" % score, res,
           targets=[("Recall@%d" % args.k, res["recall"], 0.85),
                    ("MRR", res["mrr"], 0.70),
                    ("拒答准确率", res["refusal_acc"], 0.90)])
    return 0


if __name__ == "__main__":
    sys.exit(main())
