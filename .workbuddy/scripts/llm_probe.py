#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
DeepSeek / OpenAI 兼容接口探针（零第三方依赖，仅标准库）。

用途：
  1) 冒烟：Key 是否有效、模型名是否可用
  2) 流式计时：**首个 reasoning 帧 / 首个 content 帧 / 结束** 三个时刻
     —— 用来为「首 token 超时」取值，并判断思考流是否会让用户长时间零输出
  3) 故障注入：401（错 key）/ 400（错模型名）分别返回什么，供 LlmErrorMapper 对表

为什么不用 curl：流式计时需要逐帧时间戳，curl 管道里拿不到。

用法：
  python llm_probe.py                 # 跑全部探针
  python llm_probe.py --timing        # 只跑流式计时
  python llm_probe.py --errors        # 只跑故障注入
  python llm_probe.py --effort low    # 指定思考档位（low/high/max）

Key 来源：环境变量 LLM_API_KEY，或 --key 参数。**不要把 key 写进本文件。**
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com")
# 不写 /v1 —— 与后端「base-url 与 path 全配置化」保持同一口径
PATH = os.environ.get("LLM_PATH", "/chat/completions")
MODEL = os.environ.get("LLM_MODEL", "deepseek-v4-flash")

# 走本机代理会连不上/拿到 502，显式绕过（与 curl --noproxy '*' 同理）
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def post(path, payload, key, stream=False, timeout=90):
    req = urllib.request.Request(
        BASE.rstrip("/") + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + key,
            "Accept": "text/event-stream" if stream else "application/json",
        },
        method="POST",
    )
    return NO_PROXY.open(req, timeout=timeout)


def get_models(key, timeout=30):
    req = urllib.request.Request(
        BASE.rstrip("/") + "/models",
        headers={"Authorization": "Bearer " + key},
    )
    with NO_PROXY.open(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def probe_models(key):
    print("=== [1] 模型列表 ===")
    try:
        data = get_models(key)
    except urllib.error.HTTPError as e:
        print("  ✗ HTTP %s  %s" % (e.code, e.read().decode("utf-8", "replace")[:200]))
        return False
    ids = []
    for m in data.get("data", []):
        mid = m.get("id")
        ids.append(mid)
        effort = (m.get("effort") or {})
        print("  · id=%-20s name=%-22s ctx=%s out=%s effort=%s"
              % (mid, m.get("name"), m.get("context_window"),
                 m.get("max_output_tokens"), effort.get("supported_levels")))
    print("  共 %d 个模型" % len(ids))
    if MODEL not in ids:
        print("  ⚠ 配置的 model=%s 不在列表里（可能是别名，需实调验证）" % MODEL)
    return True


def probe_timing(key, effort=None, max_tokens=256):
    """逐帧读 SSE，记录三个关键时刻。"""
    print("=== [2] 流式计时（effort=%s, max_tokens=%d）===" % (effort or "默认", max_tokens))
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": "你是社区心理健康助手，回答简洁，不超过 50 字。"},
            {"role": "user", "content": "我最近两周总是睡不着，白天没精神，我该怎么办？"},
        ],
        "stream": True,
        "max_tokens": max_tokens,
    }
    if effort:
        payload["effort"] = effort

    t0 = time.time()
    t_first_any = t_first_reason = t_first_content = None
    reason_chars = content_chars = frames = 0
    finish_reason = None
    usage = None

    try:
        resp = post(PATH, payload, key, stream=True)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        print("  ✗ HTTP %s  %s" % (e.code, body[:300]))
        return None
    except Exception as e:
        print("  ✗ 连接失败: %r" % (e,))
        return None

    with resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            frames += 1
            now = time.time() - t0
            if t_first_any is None:
                t_first_any = now
            try:
                obj = json.loads(data)
            except Exception:
                continue
            if obj.get("usage"):
                usage = obj["usage"]
            for ch in obj.get("choices") or []:
                if ch.get("finish_reason"):
                    finish_reason = ch["finish_reason"]
                d = ch.get("delta") or {}
                rc = d.get("reasoning_content")
                ct = d.get("content")
                if rc:
                    if rc.strip() and t_first_reason is None:
                        t_first_reason = now
                    reason_chars += len(rc)
                if ct:
                    if ct.strip() and t_first_content is None:
                        t_first_content = now
                    content_chars += len(ct)

    total = time.time() - t0
    print("  帧数=%d  思考字数=%d  正文字数=%d  finish=%s" % (frames, reason_chars, content_chars, finish_reason))
    print("  首个任意帧      : %s" % fmt(t_first_any))
    print("  首个思考帧      : %s" % fmt(t_first_reason))
    print("  首个正文帧 (TTFT): %s   ← 首 token 超时要大于它" % fmt(t_first_content))
    print("  整体结束        : %.2f s" % total)
    if usage:
        print("  usage           : prompt=%s completion=%s cached=%s"
              % (usage.get("prompt_tokens"), usage.get("completion_tokens"),
                 usage.get("prompt_cache_hit_tokens")))
    if content_chars == 0:
        print("  ⚠ 正文为空 —— max_tokens 被思考过程吃光了（推理模型必须给足 max_tokens）")
    print()
    return {"ttft": t_first_content, "ttfr": t_first_reason, "total": total}


def fmt(v):
    return "%.2f s" % v if v is not None else "未出现"


def probe_errors(key):
    print("=== [3] 故障注入（供 LlmErrorMapper 对表）===")
    cases = [
        ("错 Key → 期望 401", "sk-invalid-key-for-probe", "deepseek-v4-flash"),
        ("错模型名 → 期望 400", key, "deepseek-chat-retired-probe"),
    ]
    for title, k, m in cases:
        payload = {"model": m, "messages": [{"role": "user", "content": "hi"}],
                   "stream": False, "max_tokens": 4}
        try:
            with post(PATH, payload, k) as r:
                print("  %-22s → HTTP %s  %s" % (title, r.status, r.read().decode("utf-8", "replace")[:200]))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            print("  %-22s → HTTP %s  %s" % (title, e.code, body[:220]))
        except Exception as e:
            print("  %-22s → 连接异常 %r" % (title, e))
    print()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", default=os.environ.get("LLM_API_KEY"))
    ap.add_argument("--effort", default=None, choices=["low", "high", "max"])
    ap.add_argument("--timing", action="store_true")
    ap.add_argument("--errors", action="store_true")
    ap.add_argument("--all-effort", action="store_true", help="对比 low/默认 的 TTFT 差异")
    ap.add_argument("--max-tokens", type=int, default=256,
                    help="推理模型的思考会先吃掉预算，探针默认 256 常常量不出 TTFT，按需调大")
    a = ap.parse_args()

    if not a.key:
        print("缺少 key：设环境变量 LLM_API_KEY 或用 --key 传入")
        return 2
    print("base=%s  path=%s  model=%s" % (BASE, PATH, MODEL))
    print()

    only = a.timing or a.errors
    if not only:
        probe_models(a.key)
        print()
    if a.timing or not only:
        probe_timing(a.key, a.effort, a.max_tokens)
    if a.all_effort:
        for e in ["low", "high"]:
            probe_timing(a.key, e, a.max_tokens)
    if a.errors or not only:
        probe_errors(a.key)
    return 0


if __name__ == "__main__":
    sys.exit(main())
