"""通义（阿里云百炼）Embedding 探针 —— Phase 3 的另一半前置验证。

为什么要单独验
--------------
本项目对话侧用 DeepSeek，**向量化必须换第二家**（DeepSeek 无 embeddings 端点）。
通义走的是 OpenAI 兼容协议，但兼容接口有几个**静默失败**的口子：
  * `output_type=sparse` 传给兼容接口 -> HTTP 200 但 embedding 为空（本项目不需要 sparse，
    因为稀疏向量由 Milvus 的 BM25 Function 生成，但要知道这个坑存在）
  * API Key 分地域：中国区 Key 只能打中国区 endpoint，打错是鉴权失败而非 404
  * 维度不写就取默认值，而**换了维度后新旧向量不可比较** —— 必须一次定死

依赖：**零第三方依赖**（标准库 urllib）。

用法
----
    export DASHSCOPE_API_KEY=sk-xxxx          # 或用 --key 传
    python qwen_embed_probe.py                # 四断言
    python qwen_embed_probe.py --region intl  # 新加坡地域
    python qwen_embed_probe.py --inject       # 额外跑故障注入（错 Key -> 401）
    python qwen_embed_probe.py --dim 512      # 试别的维度

核实用例
--------
  [1/4] 模型列表   —— GET /models（免费），确认服务可达 + 目标模型名存在
  [2/4] 单条向量   —— 返回维度必须等于 --dim（本项目与 Milvus schema 对齐：1024）
  [3/4] 批量 10 条 —— 通义单次上限 10 条，验证批量路径
  [4/4] 语义 sanity —— 「抑郁」与「情绪低落」的相似度应明显高于「骨折」
"""
import argparse
import json
import math
import os
import sys
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

REGIONS = {
    "cn": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "intl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
}


def brief(raw, limit=300):
    raw = (raw or "").replace("\n", " ")
    return raw if len(raw) <= limit else raw[:limit] + " …(截断)"


def mask(key):
    if not key:
        return "(未设置)"
    return key[:6] + "…" + key[-4:] if len(key) > 12 else "***"


class Client:
    def __init__(self, base, key, timeout=30):
        self.base = base.rstrip("/")
        self.key = key
        self.timeout = timeout
        # 绕开本机 HTTP_PROXY：外部 API 本该走代理，但本机代理只对特定出网有效，
        # 实测直连 dashscope 可用；若你的网络需要代理，去掉这一行即可。
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, method, path, payload=None):
        url = self.base + path
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Content-Type", "application/json")
        if self.key:
            req.add_header("Authorization", "Bearer " + self.key)
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                return resp.status, self._parse(raw), raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            return e.code, self._parse(raw), raw
        except Exception as e:
            return None, None, "%s: %s" % (type(e).__name__, e)

    @staticmethod
    def _parse(raw):
        try:
            return json.loads(raw)
        except Exception:
            return None

    def embed(self, texts, model, dim):
        return self.request("POST", "/embeddings", {
            "model": model,
            "input": texts,
            "dimensions": dim,
            "encoding_format": "float",
        })


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def die(msg):
    print("\n" + msg)
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", default=os.environ.get("DASHSCOPE_API_KEY", ""))
    ap.add_argument("--region", choices=sorted(REGIONS), default="cn")
    ap.add_argument("--model", default="text-embedding-v4")
    ap.add_argument("--dim", type=int, default=1024)
    ap.add_argument("--inject", action="store_true", help="额外跑故障注入（错 Key -> 401）")
    args = ap.parse_args()

    base = REGIONS[args.region]
    key = (args.key or "").strip()

    print("=" * 68)
    print("通义 Embedding 探针 · %s" % base)
    print("model=%s  dim=%d  region=%s  key=%s" % (args.model, args.dim, args.region, mask(key)))
    print("=" * 68)

    if not key:
        die("未提供 API Key。\n"
            "  取法：阿里云百炼控制台 -> API-KEY 管理 -> 创建\n"
            "  传入：export DASHSCOPE_API_KEY=sk-xxxx   或   --key sk-xxxx\n"
            "  注意：**中国区 Key 只能用 cn 地域**，新加坡 Key 只能用 intl（--region intl）。")

    cli = Client(base, key)

    # ---------------- [1/4] 模型列表 ----------------
    print("\n[1/4] 模型列表（GET /models，免费不扣费）")
    status, payload, raw = cli.request("GET", "/models")
    if status == 200 and payload:
        ids = [m.get("id") for m in (payload.get("data") or []) if isinstance(m, dict)]
        print("  ✓ 可达，共 %d 个模型" % len(ids))
        hit = [i for i in ids if i and "embedding" in i.lower()]
        if hit:
            print("  · 向量模型：%s" % ", ".join(hit[:10]))
        if ids and args.model not in ids and not hit:
            print("  · 注意：模型列表里没有 %s，先确认名字（有可能是端点不返回全量）" % args.model)
    elif status == 404:
        print("  · /models 端点不存在（兼容接口未暴露）—— 跳过，用最小调用验证 Key")
    elif status in (401, 403):
        die("鉴权失败（HTTP %s）：%s\n"
            "  ① Key 是否写错/过期；② Key 地域与 --region 是否匹配。\n"
            "  原始响应：%s" % (status, "见下", brief(raw)))
    else:
        print("  · 非预期（http=%s）：%s —— 继续用实际调用验证" % (status, brief(raw, 160)))

    # ---------------- [2/4] 单条向量 ----------------
    print("\n[2/4] 单条中文向量")
    status, payload, raw = cli.embed(["舍曲林常见不良反应包括恶心与失眠"], args.model, args.dim)
    if status != 200 or not payload:
        die("调用失败（http=%s）。原始响应：%s\n"
            "  排查：模型名是否有效、地域是否匹配、是否需要开通百炼服务。" % (status, brief(raw, 500)))
    vec = (payload.get("data") or [{}])[0].get("embedding") or []
    if len(vec) != args.dim:
        die("维度不符：期望 %d，实得 %d。\n"
            "  维度一旦改变，**所有已入库向量都要重新生成** —— 先定死再继续。"
            % (args.dim, len(vec)))
    print("  ✓ 维度 = %d（与 Milvus schema 的 dim 一致）" % len(vec))
    usage = payload.get("usage") or {}
    print("  · token 用量：%s" % usage)

    # ---------------- [3/4] 批量 ----------------
    print("\n[3/4] 批量 10 条（通义单次上限）")
    texts = ["第 %d 条测试文本，用于验证批量向量化路径。" % i for i in range(1, 11)]
    status, payload, raw = cli.embed(texts, args.model, args.dim)
    if status != 200 or not payload:
        die("批量调用失败（http=%s）：%s" % (status, brief(raw, 400)))
    n = len(payload.get("data") or [])
    if n != 10:
        die("返回条数不符：期望 10，实得 %d（批大小是否被限制？）" % n)
    print("  ✓ 返回 10 条向量")

    # ---------------- [4/4] 语义 sanity ----------------
    print("\n[4/4] 语义 sanity check（中文语义空间是否正常）")
    status, payload, raw = cli.embed(["抑郁", "情绪低落", "骨折"], args.model, args.dim)
    if status != 200 or not payload:
        die("调用失败：%s" % brief(raw, 300))
    embs = [d.get("embedding") for d in (payload.get("data") or [])]
    if len(embs) != 3:
        die("返回条数不符：期望 3，实得 %d" % len(embs))
    sim_near = cosine(embs[0], embs[1])
    sim_far = cosine(embs[0], embs[2])
    print("  · cos(抑郁, 情绪低落) = %.4f" % sim_near)
    print("  · cos(抑郁, 骨折)     = %.4f" % sim_far)
    if sim_near <= sim_far:
        die("语义相似度异常：近义词没有比无关词更接近 —— 检查模型名/维度是否被服务端忽略。")
    print("  ✓ 近义 > 无关，语义空间正常（阈值过滤可据此设定）")

    # ---------------- 故障注入 ----------------
    if args.inject:
        print("\n[附加] 故障注入：错 Key")
        bad = Client(base, "sk-invalid-key-for-probe")
        status, payload, raw = bad.embed(["test"], args.model, args.dim)
        if status in (401, 403):
            err = (payload or {}).get("error") or {}
            print("  ✓ 错 Key -> HTTP %s，code=%s（可据此做鉴权失败归类）"
                  % (status, err.get("code")))
        else:
            print("  · 意外：错 Key 返回 http=%s body=%s" % (status, brief(raw, 200)))

    print("\n" + "=" * 68)
    print("全部通过 —— 向量化这一路可用于 Phase 3")
    print("=" * 68)
    print("写入配置时需要定死（三者必须一致）：")
    print("  · model  = %s" % args.model)
    print("  · base-url = %s" % base)
    print("  · dim    = %d（同时写进 Milvus 的 dense_vector dim）" % args.dim)


if __name__ == "__main__":
    main()
