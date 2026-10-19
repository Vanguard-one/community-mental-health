#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
提示词回归验证：改完 PromptAssets / ragBlock 后，先在这里真调一次 LLM。

为什么必须真跑（硬约定 #69）：
  提示词是**文本**，Java 编译通过 ≠ 模型会照做。上一轮的教训是
  「检索命中了、citations 也渲染了，但模型仍然拒绝回答」—— 那类问题
  只有真调一次才会暴露，光读代码看不出来。

这个脚本做三件事：
  ① 从 Java 源码里**读出**提示词常量（不手抄，避免与线上文案漂移）；
  ② 从 MySQL 取真实分块当参考资料（也不用编造的假资料）；
  ③ 用 OpenAI 兼容协议发一次非流式请求，打印模型回答，
     并给出「是否基于资料作答」的启发式判定。

用法：
  python prompt_probe.py                          # 默认问爬虫问题（有资料）
  python prompt_probe.py --query "什么是 xpath"
  python prompt_probe.py --no-knowledge           # 模拟「无资料」分支
  python prompt_probe.py --dry-run                # 只打印将发送的 messages，不调 API
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAVA = os.path.join(BASE_DIR, "psycare-server", "src", "main", "java", "com", "cmh", "psycare")
PROMPT_ASSETS = os.path.join(JAVA, "ai", "PromptAssets.java")
YML = os.path.join(BASE_DIR, "psycare-server", "src", "main", "resources", "application.yml")

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# ---------------------------------------------------------------- 读配置

def read_llm():
    cfg = open(YML, encoding="utf-8").read()
    llm = cfg.split("llm:", 1)[1].split("embedding:", 1)[0]

    def pick(key, default=None):
        m = re.search(r"^\s+%s:\s*(\S*)\s*$" % re.escape(key), llm, re.M)
        return m.group(1) if m and m.group(1) else default

    return {
        "base_url": pick("base-url"),
        "path": pick("path"),
        "api_key": pick("api-key"),
        "model": pick("model"),
        "max_tokens": int(pick("max-tokens", "8192")),
    }


# ---------------------------------------------------------------- 读提示词常量

def read_text_block(name):
    """从 PromptAssets.java 里抽出 public static final String NAME = \"\"\"...\"\"\"; 的内容。"""
    src = open(PROMPT_ASSETS, encoding="utf-8").read()
    pat = re.compile(
        r'public\s+static\s+final\s+String\s+' + re.escape(name)
        + r'\s*=\s*"""(.*?)""";', re.S)
    m = pat.search(src)
    if not m:
        raise SystemExit("在 PromptAssets.java 里找不到常量 %s" % name)
    text = m.group(1)
    # Java 文本块会自动去掉每行公共缩进；这里按最小缩进手工还原
    lines = [ln for ln in text.split("\n")]
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    indents = [len(ln) - len(ln.lstrip()) for ln in lines if ln.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(ln[cut:] if ln.strip() else "" for ln in lines)


def read_rag_block():
    """ragBlock 的文案在 ContextAssembler 里，用文本块拼的，单独抽。"""
    src = open(os.path.join(JAVA, "ai", "ContextAssembler.java"), encoding="utf-8").read()
    i = src.find('sb.append("""')
    if i < 0:
        raise SystemExit("ContextAssembler 里找不到 ragBlock 的文本块")
    j = src.find('"""', i + len('sb.append("""'))
    # 结束标记是 '"""' 后跟 ');'
    m = re.search(r'sb\.append\("""(.*?)"""\)', src, re.S)
    if not m:
        raise SystemExit("ragBlock 文本块解析失败")
    text = m.group(1)
    lines = text.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    indents = [len(ln) - len(ln.lstrip()) for ln in lines if ln.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(ln[cut:] if ln.strip() else "" for ln in lines)


# ---------------------------------------------------------------- 取真实资料

def load_chunks(limit=3):
    """从 MySQL 取真实子块正文（与线上回表取的父块同源）。"""
    try:
        import pymysql
    except ImportError:
        print("[warn] 隔离环境无 pymysql，改用内置样例资料", file=sys.stderr)
        return ["《爬虫知识》\nrequests.get(url, headers=..., timeout=...)"]
    conn = pymysql.connect(host="localhost", port=3306, user="root",
                           password="123456", database="psycare", charset="utf8mb4")
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT c.section, c.content FROM kb_chunk c "
            "JOIN kb_document d ON d.id = c.doc_id "
            "WHERE c.parent_id IS NOT NULL AND d.status = 'READY' "
            "ORDER BY c.id LIMIT %s", (limit,))
        rows = cur.fetchall()
    finally:
        conn.close()
    out = []
    for section, content in rows:
        head = "《爬虫知识》" + (" " + section if section else "")
        body = (content or "").replace("\n", " ").strip()
        out.append(head + "\n" + body[:1200])
    return out or ["（库里没有可用分块）"]


# ---------------------------------------------------------------- 组装 messages

def build_messages(query, knowledge, no_knowledge):
    msgs = [
        {"role": "system", "content": read_text_block("IDENTITY")},
        {"role": "system", "content": read_text_block("SAFETY_BOUNDARY")},
        {"role": "system", "content": read_text_block("OUTPUT_RULES")},
        {"role": "system", "content": read_text_block("INTERVIEW_FLOW")},
        {"role": "system", "content": "【本次对话背景】\n- 本次对话不是从测评结果发起的，没有测评背景信息。\n"
                                      "- 当前问诊阶段：建立联结（第 1 / 3 阶段）。本阶段目标：共情、确认来意。"},
    ]
    if no_knowledge or not knowledge:
        msgs.append({"role": "system", "content": read_text_block("NO_KNOWLEDGE_HINT")})
    else:
        block = "【参考资料】\n"
        for i, k in enumerate(knowledge, 1):
            block += "[%d] %s\n" % (i, k)
        block += read_rag_block()
        msgs.append({"role": "system", "content": block})
    msgs.append({"role": "user", "content": query})
    return msgs


# ---------------------------------------------------------------- 调用

def call_llm(cfg, messages):
    url = cfg["base_url"].rstrip("/") + cfg["path"]
    payload = {
        "model": cfg["model"],
        "messages": messages,
        "max_tokens": cfg["max_tokens"],
        "stream": False,
    }
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + cfg["api_key"]})
    try:
        with OPENER.open(req, timeout=120) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:400]
        raise SystemExit("LLM HTTP %s：%s" % (e.code, body))


# ---------------------------------------------------------------- 判定

# 「推脱」话术：把**平台定位当能力上限**说出来（「这不是我的业务范围」）。
# ⚠️ 刻意不收「没有资料」「没法给准确说法」这类 —— 那是**诚实的边界声明**，
#    正是无资料分支要的正确行为，收进来会造成误报（实测踩过一次）。
REFUSAL_MARKS = ["帮不上忙", "不是我这", "不属于我", "只能聊心理健康",
                 "主要负责心理健康", "另找渠道", "这方面不是我的"]
# 资料里实际出现的词，模型用了就说明它真的读了资料
GROUND_MARKS = ["requests", "Session", "lxml", "Selenium", "Playwright", "aiohttp",
                "robots", "429", "403", "代理", "解析", "异步", "xpath", "Xpath",
                "BeautifulSoup", "MongoDB", "cookie", "并发"]


def judge(answer):
    refused = [m for m in REFUSAL_MARKS if m in answer]
    grounded = [m for m in GROUND_MARKS if m in answer]
    return refused, grounded


def main():
    ap = argparse.ArgumentParser(description="提示词回归验证（真调 LLM）")
    ap.add_argument("--query", default="爬虫的信息")
    ap.add_argument("--no-knowledge", action="store_true", help="模拟无资料分支")
    ap.add_argument("--dry-run", action="store_true", help="只打印 messages，不调 API")
    args = ap.parse_args()

    knowledge = [] if args.no_knowledge else load_chunks(3)
    messages = build_messages(args.query, knowledge, args.no_knowledge)

    print("=" * 66)
    print("  提示词回归验证")
    print("=" * 66)
    print("  query      : %s" % args.query)
    print("  资料条数   : %d" % len(knowledge))
    print("  messages   : %d 条，共 %d 字"
          % (len(messages), sum(len(m["content"]) for m in messages)))
    print()

    for i, m in enumerate(messages):
        tag = "system" if m["role"] == "system" else "user  "
        head = m["content"].split("\n")[0][:56]
        print("  [%d] %s | %s" % (i, tag, head))
    print()

    if args.dry_run:
        print("（--dry-run：未调用 API）")
        return 0

    cfg = read_llm()
    print("  调用 %s%s  模型 %s" % (cfg["base_url"], cfg["path"], cfg["model"]))
    print("-" * 66)
    resp = call_llm(cfg, messages)
    choice = (resp.get("choices") or [{}])[0]
    msg = choice.get("message") or {}
    answer = (msg.get("content") or "").strip()
    finish = choice.get("finish_reason")
    usage = resp.get("usage") or {}

    print(answer if answer else "（正文为空！finish_reason=%s）" % finish)
    print("-" * 66)
    print("  finish_reason : %s" % finish)
    print("  tokens        : prompt=%s completion=%s reasoning=%s"
          % (usage.get("prompt_tokens"), usage.get("completion_tokens"),
             usage.get("completion_tokens_details", {}).get("reasoning_tokens")))

    refused, grounded = judge(answer)
    print()
    print("  ── 判定 ──")
    if not answer:
        print("  ✗ 正文为空（max_tokens 被思考吃光？）")
    if not refused:
        print("  ✓ 未出现推脱话术")
    else:
        print("  ✗ 仍出现推脱话术：%s" % refused)
    if grounded:
        print("  ✓ 命中资料关键词 %d 个：%s" % (len(grounded), grounded[:8]))
    else:
        print("  ⚠ 未命中任何资料关键词 —— 模型可能没在用资料")
    return 0


if __name__ == "__main__":
    sys.exit(main())
