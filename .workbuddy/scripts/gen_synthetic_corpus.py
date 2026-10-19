#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
合成语料生成器（Phase 3 · 阶段一）。

读 `psycare-server/corpus/topics.yaml` 的主题清单，逐条调用 DeepSeek 生成
结构化正文，输出 JSONL（一行一条），字段与 `DocumentParser.parseJsonl` 的契约一致：

    {"doc_title":"...","source_type":"SYNTHETIC","publish_year":2024,
     "section":"第 1 节 PHQ-9 / 1.1 量表用途","content":"...","tags":["PHQ-9"]}

⚠️ 合成语料的合法用途只有三个：① 打通链路 ② 构建检索评测集 ③ 演示。
它本质是「模型关于医疗知识的记忆的再表述」，**可能包含错误**，不得用于对外回答。
所以 source_type 强制 SYNTHETIC，且**不接受 --source-type 覆盖**。

设计要点
--------
* **零第三方 HTTP 依赖**：用标准库 urllib（隔离环境里没有 requests，本机 pip 出网被沙箱拦）。
  只有 YAML 解析用 PyYAML（隔离环境已装 6.0.3）。
* **断点续跑**：每生成一条就 append + flush。重跑时按 (doc_title, section) 跳过已有条目 ——
  90 次调用里失败一次就要全量重跑，是不可接受的。
* **逐条报错不中断**：某条被限流或超时，记一行 warning 继续下一条。

用法
----
  # 先看会生成什么（不调模型、不花钱）
  python gen_synthetic_corpus.py --dry-run

  # 只跑一个主题域的前 3 条，验证 prompt
  python gen_synthetic_corpus.py --domain 量表条目解释 --limit 3

  # 全量（约 90 条；4 并发）
  LLM_API_KEY=sk-xxx python gen_synthetic_corpus.py

Key 来源：环境变量 LLM_API_KEY，或 --key 参数。**不要把 key 写进本文件。**
"""

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import yaml
except ImportError:
    sys.exit("缺少 PyYAML。请用隔离环境的解释器运行：\n"
             "  C:/Users/Lenovo/.workbuddy/binaries/python/envs/default/Scripts/python.exe "
             + os.path.basename(__file__))

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TOPICS = os.path.normpath(os.path.join(HERE, "..", "..", "psycare-server", "corpus", "topics.yaml"))
DEFAULT_OUT = os.path.normpath(os.path.join(HERE, "..", "..", "psycare-server", "corpus", "synthetic_corpus.jsonl"))

BASE = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com")
PATH = os.environ.get("LLM_PATH", "/chat/completions")
MODEL = os.environ.get("LLM_MODEL", "deepseek-flash")

# 走本机代理会连不上（与 curl --noproxy '*' 同理）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

_print_lock = threading.Lock()


def log(msg):
    with _print_lock:
        print(msg, flush=True)


# ==================== 主题装载 ====================

def load_jobs(topics_path, only_domain=None):
    with open(topics_path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    defaults = cfg.get("defaults") or {}
    constraints = cfg.get("constraints") or []
    jobs = []
    for dom in cfg.get("domains") or []:
        if only_domain and dom.get("domain") != only_domain:
            continue
        for t in dom.get("topics") or []:
            jobs.append({
                "doc_title": dom["doc_title"],
                "source_type": "SYNTHETIC",          # 强制，不提供覆盖入口
                "publish_year": defaults.get("publish_year", 2024),
                "section": t["section"],
                "brief": t["brief"],
                "tags": dom.get("tags") or [],
                "domain": dom.get("domain", ""),
                "min_chars": defaults.get("min_chars", 380),
                "max_chars": defaults.get("max_chars", 560),
                "constraints": constraints,
            })
    return jobs


def load_done(out_path):
    """已完成的 (doc_title, section) 集合 —— 断点续跑的依据"""
    done = set()
    if not os.path.exists(out_path):
        return done
    with open(out_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
            except ValueError:
                continue
            done.add((o.get("doc_title", ""), o.get("section", "")))
    return done


# ==================== 调用 ====================

def build_messages(job):
    constraints = "\n".join("- " + c for c in job["constraints"])
    system = (
        "你是社区心理健康服务系统的科普语料编写助手，为居民自助阅读而写。\n"
        "硬性要求：\n" + constraints + "\n"
        "输出格式：只输出正文本身，不要标题、不要 markdown 标记、不要分点编号、不要任何前后缀说明。"
    )
    user = (
        "写作主题：{sec}\n"
        "需要覆盖的要点：{brief}\n"
        "篇幅：{mn}–{mx} 个汉字（一段或两段连续文字）。"
    ).format(sec=job["section"], brief=job["brief"], mn=job["min_chars"], mx=job["max_chars"])
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def call_llm(job, key, model, timeout=120, retries=3):
    body = json.dumps({
        "model": model,
        "messages": build_messages(job),
        "temperature": 0.4,
        # ⚠️ 2048 太小。本模型是**推理模型**，max_tokens 是「思考 + 正文」共享预算（硬约定 #22）。
        #    实测 2048 下有 11/81 条正文被截断在半句处（最短 23 字），且**每一条都是 HTTP 200**。
        "max_tokens": 4096,
        "stream": False,
    }, ensure_ascii=False).encode("utf-8")

    # 正文长度下限：低于它一律当失败重试。
    # 取配置下限的 0.7 倍而不是 1.0 倍 —— 模型偶尔写得比要求略短是正常的，
    # 但「短到只剩半句」一定是被截断的（实测最短 23 字）。
    floor = max(120, int(job.get("min_chars", 380) * 0.7))

    last = None
    for attempt in range(retries):
        req = urllib.request.Request(
            BASE.rstrip("/") + PATH,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + key,
            },
            method="POST",
        )
        try:
            with OPENER.open(req, timeout=timeout) as r:
                data = json.loads(r.read().decode("utf-8"))
            choice = data["choices"][0]
            text = (choice["message"].get("content") or "").strip()
            finish = choice.get("finish_reason")
            # ⚠️ 必须显式检查 finish_reason。被截断的正文**照样是一个非空字符串**，
            #    只判「空不空」会把它当成成功写进语料 —— 本文件曾因此漏过 11 条半句话，
            #    而它们一路混到 Phase 3 的切分/向量化里都不会报错。
            if finish == "length":
                raise ValueError("正文被 max_tokens 截断（finish_reason=length）")
            if not text:
                raise ValueError("返回正文为空（可能是 max_tokens 被思考吃光）")
            if len(text) < floor:
                raise ValueError("正文过短（%d 字 < 下限 %d），疑似截断" % (len(text), floor))
            return text
        except Exception as e:                      # noqa: BLE001 —— 逐条隔离，不该中断整批
            last = e
            if attempt < retries - 1:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError("调用失败：%s" % last)


# ==================== 主流程 ====================

def main():
    ap = argparse.ArgumentParser(description="生成合成语料 JSONL")
    ap.add_argument("--topics", default=DEFAULT_TOPICS)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--domain", help="只生成某个主题域")
    ap.add_argument("--limit", type=int, help="最多生成多少条（用于试跑）")
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--key", default=os.environ.get("LLM_API_KEY", ""))
    ap.add_argument("--dry-run", action="store_true", help="只列出将要生成的主题，不调模型")
    ap.add_argument("--no-resume", action="store_true", help="忽略已有输出，全部重跑")
    args = ap.parse_args()

    jobs = load_jobs(args.topics, args.domain)
    if args.limit:
        jobs = jobs[:args.limit]

    done = set() if args.no_resume else load_done(args.out)
    todo = [j for j in jobs if (j["doc_title"], j["section"]) not in done]

    log("主题清单：%s" % args.topics)
    log("输出文件：%s" % args.out)
    log("计划 %d 条 · 已完成 %d 条 · 本次待生成 %d 条" % (len(jobs), len(jobs) - len(todo), len(todo)))

    if args.dry_run:
        for j in todo[:10]:
            log("  · [%s] %s" % (j["domain"], j["section"]))
        if len(todo) > 10:
            log("  · ...（共 %d 条）" % len(todo))
        log("\n--dry-run 结束：未调用模型、未写文件。")
        return 0

    if not todo:
        log("没有待生成的条目（用 --no-resume 可强制全部重跑）。")
        return 0
    if not args.key:
        log("✗ 缺少 Key。设置环境变量 LLM_API_KEY，或用 --key 传入。")
        return 2

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    ok = 0
    fail = 0
    started = time.time()

    with open(args.out, "a", encoding="utf-8") as out:
        with ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
            futures = {pool.submit(call_llm, j, args.key, args.model): j for j in todo}
            for n, fut in enumerate(as_completed(futures), 1):
                job = futures[fut]
                try:
                    text = fut.result()
                    record = {
                        "doc_title": job["doc_title"],
                        "source_type": job["source_type"],
                        "publish_year": job["publish_year"],
                        "section": job["section"],
                        "content": text,
                        "tags": job["tags"],
                    }
                    # 立即落盘：断点续跑靠的就是「已完成的那部分真的在文件里」
                    out.write(json.dumps(record, ensure_ascii=False) + "\n")
                    out.flush()
                    ok += 1
                    log("[%d/%d] ✓ %s（%d 字）" % (n, len(todo), job["section"], len(text)))
                except Exception as e:              # noqa: BLE001
                    fail += 1
                    log("[%d/%d] ✗ %s —— %s" % (n, len(todo), job["section"], e))

    log("\n完成：成功 %d 条，失败 %d 条，耗时 %.1fs" % (ok, fail, time.time() - started))
    if fail:
        log("失败的条目**不在文件里**，重跑本脚本即可自动续跑（已完成的会跳过）。")
    log("下一步：把 %s 上传到管理端「知识库 → 新建 / 导入文档」（文件名以 .jsonl 结尾即走批量导入）。"
        % os.path.basename(args.out))
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
