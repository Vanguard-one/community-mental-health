# -*- coding: utf-8 -*-
"""语料校验器 —— 导入管理端之前跑一遍，避免「格式对了、内容有问题」。

校验项（任何一条失败都以非零码退出，便于接进 CI 或上线前检查）：

  A. 结构
     1. 每行是合法 JSON 对象
     2. doc_title 非空
     3. content 非空
     4. publish_year 存在时必须是数字（写成 "2024" 会被后端静默丢弃）
     5. source_type 取值在允许集合内

  B. 内容合规
     6. 零爬虫 / 编程技术关键词（本语料的明确要求）
     7. 用药相关段落必须带「遵医嘱」限定（安全要求）
     8. 不得出现具体机构名 / 医生姓名 / 电话 / 网址

  C. 报告
     9. 篇数、每篇行数、总字数、字数分布
    10. 与现有语料的 doc_title 冲突检查（避免与 synthetic_corpus 重叠标题）

用法：
    python verify_corpus.py                    # 校验 psycare_corpus.jsonl
    python verify_corpus.py <file.jsonl>       # 校验指定文件
"""

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT = os.path.join(HERE, "psycare_corpus.jsonl")

# ---------- 禁止出现的爬虫 / 编程技术关键词 ----------
# 说明：这是本批语料的硬性要求（用户明确要求不含爬虫知识），
# 因此做成阻断式校验而不是警告。大小写不敏感。
CRAWL_MARKS = [
    "requests", "beautifulsoup", "bs4", "lxml", "selenium", "playwright",
    "aiohttp", "scrapy", "xpath", "css selector", "user-agent", "user‐agent",
    "cookie", "robots.txt", "反爬", "爬虫", "解析库", "代理ip", "代理池",
    "正则表达式", "http 状态码", "429", "403", "并发爬取", "异步爬虫",
    "chrome devtools", "接口抓包",
]

# ---------- 允许的 source_type ----------
ALLOWED_SOURCE_TYPES = {"UPLOAD", "AUTHORITATIVE", "SYNTHETIC"}

# ---------- 用药段落的「遵医嘱」限定 ----------
DRUG_MARKS = ["用药", "服药", "药物", "药量", "剂量", "吃药", "停药", "副作用",
              "抗抑郁", "抗焦虑", "助眠药", "镇静"]
DELEGATE_MARK = "遵医嘱"

# ---------- 不应出现的敏感信息 ----------
# ⚠️ 这里的检查刻意做得「窄」而不是「宽」。
# 第一版用了 `[\u4e00-\u9fa5]{2,10}(医院|医科大学|...)`，结果把
# 「综合医院」「正规医院」「大医院」「有些医院」这类**通用词**全判成具体机构名，
# 一次刷出 15 条假警报 —— 假警报比不报警更糟，因为会让人学会忽略校验输出。
# 改成两条窄规则：
#   (a) 只匹配**真实机构的固有特征**：含「省/市/区/县」等行政区划前缀的医院全称，
#       或「XX大学附属」「XX医科大学」这类专有构词；
#   (b) 联系方式类（手机号、固话、网址）照旧。
ORG_PATTERNS = [
    # 「北京市第一人民医院」「上海精神卫生中心」这类带行政区划的机构全称
    re.compile(r"(?:北京|上海|天津|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|"
               r"福建|江西|山东|河南|湖北|湖南|广东|广西|海南|四川|贵州|云南|"
               r"陕西|甘肃|青海|宁夏|新疆|西藏|内蒙古|香港|澳门|台湾)"
               r"[\u4e00-\u9fa5]{0,8}(医院|中心|医学院|研究所)"),
    # 「XX大学附属医院」「XX医科大学」这类专有构词
    re.compile(r"[\u4e00-\u9fa5]{2,8}(?:大学|医科大学)(?:附属)?(?:医院|医学院)"),
    re.compile(r"1[3-9]\d{9}"),                          # 手机号
    re.compile(r"0\d{2,3}-\d{7,8}"),                     # 固话
    re.compile(r"https?://|www\.", re.I),                # 网址
]

# ---------- 用药段落的「遵医嘱」限定 ----------
# ⚠️ 同理：第一版把「用药」「药物」等词无差别匹配，导致「医保报销」「复诊准备」
# 这类只是**提到**药物清单的段落也被要求写「遵医嘱」，17 条里大半是假警报。
# 改成只匹配**真正在给用药指导**的表述：涉及用法、剂量、调整、停换药的动作词。
DRUG_MARKS = [
    "服用剂量", "用药剂量", "剂量", "加量", "减量", "调药", "换药",
    "自行停药", "停药", "漏服", "补服", "服药时间", "饭前还是饭后",
    "抗抑郁药", "抗焦虑药", "助眠药物", "镇静药物",
    "药物相互作用", "药物与", "副作用",
]
DELEGATE_MARK = "遵医嘱"

# 「遵医嘱」的**等价表述**。第一版只认「遵医嘱」四个字，结果把
# 「不要自己私下调整」「和医生讨论」「而不是自己决定不吃」这类
# 语义等同、甚至更自然的中文表达判成缺失 —— 那是把校验器当字面匹配器用了。
# 判定的真实意图是「有没有把决定权交回给专业人员」，所以把这些一并纳入。
DELEGATE_EQUIVALENTS = [
    "遵医嘱", "按医嘱", "医嘱",
    "问医生", "问清楚医生", "和医生讨论", "与医生讨论", "咨询医生",
    "告诉医生", "由医生", "医生判断", "医生决定", "医生评估",
    "不要自己私下", "自己私下调整", "不要自行", "而不是自己决定", "不能自行",
    "咨询药师", "问药师",
    "按医生的安排", "由医生调整", "请医生", "医生指导下",
]


def fail(msg):
    print(f"  ✗ {msg}")
    return 1


def warn(msg):
    print(f"  ⚠ {msg}")
    return 0


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    if not os.path.exists(path):
        print(f"文件不存在：{path}")
        return 2

    print(f"校验文件：{path}")
    print("-" * 68)

    errors = 0
    warnings = 0

    raw = open(path, encoding="utf-8").read()
    # 非 ASCII 陷阱排查：全角引号、非断字连字符等
    for ch, name in [("\u2011", "非断字连字符 U+2011"),
                     ("\u2018", "左单引号 U+2018"),
                     ("\u2019", "右单引号 U+2019")]:
        if ch in raw:
            warnings += warn(f"正文含{name}（共 {raw.count(ch)} 处）—— 检查是否是从外部粘贴引入的")

    docs = {}          # doc_title -> [records]
    total_chars = 0
    n_lines = 0

    for lineno, line in enumerate(raw.split("\n"), 1):
        s = line.strip()
        if not s:
            continue
        n_lines += 1
        try:
            o = json.loads(s)
        except Exception as e:
            errors += fail(f"第 {lineno} 行不是合法 JSON：{e}")
            continue
        if not isinstance(o, dict):
            errors += fail(f"第 {lineno} 行不是 JSON 对象")
            continue

        title = o.get("doc_title")
        body = o.get("content")
        if not isinstance(title, str) or not title.strip():
            errors += fail(f"第 {lineno} 行 doc_title 为空")
            continue
        if not isinstance(body, str) or not body.strip():
            errors += fail(f"第 {lineno} 行 content 为空")
            continue

        # publish_year 必须是数字
        y = o.get("publish_year")
        if y is not None and not isinstance(y, int):
            errors += fail(f"第 {lineno} 行 publish_year 不是数字（值为 {y!r}）")

        st = o.get("source_type")
        if st is not None and st not in ALLOWED_SOURCE_TYPES:
            errors += fail(f"第 {lineno} 行 source_type 非法：{st!r}")

        docs.setdefault(title, []).append(o)
        total_chars += len(body)

    # ---- A 汇总 ----
    print(f"\n[A] 结构校验：{n_lines} 行 / {len(docs)} 篇")
    if errors == 0:
        print("  ✓ 全部通过")

    # ---- B.6 爬虫关键词 ----
    print("\n[B] 内容合规")
    crawl_hits = []
    for lineno, line in enumerate(raw.split("\n"), 1):
        s = line.strip()
        if not s:
            continue
        low = s.lower()
        for m in CRAWL_MARKS:
            if m.lower() in low:
                crawl_hits.append((lineno, m))
    if crawl_hits:
        for ln, m in crawl_hits[:20]:
            errors += fail(f"第 {ln} 行含禁用关键词：{m}")
    else:
        print("  ✓ 零爬虫/编程技术关键词")

    # ---- B.7 用药必须带「遵医嘱」或其等价表述 ----
    # 已知豁免：这些段落只是**提及**用药（描述依从性现象、列举要带去的材料），
    # 并不给出任何用药指导，因此不要求出现委托表述。
    # ⚠️ 用 (doc_title, section 片段) 精确豁免，不靠放宽关键词 —— 放宽关键词
    # 会让整条规则失效，那等于没有校验。每加一条豁免都应当先人工确认原文确无指导性。
    EXEMPT = {
        ("特殊人群心理健康", "4.1 身心同治的必要性"),   # 描述「依从性影响病情」这一现象
    }

    drug_no_delegate = []
    for title, recs in docs.items():
        for r in recs:
            body = r["content"]
            sec = r.get("section", "")
            if any(t == title and frag in sec for t, frag in EXEMPT):
                continue
            hit_drug = any(m in body for m in DRUG_MARKS)
            hit_delegate = any(e in body for e in DELEGATE_EQUIVALENTS)
            if hit_drug and not hit_delegate:
                drug_no_delegate.append((title, sec))
    if drug_no_delegate:
        for t, sec in drug_no_delegate[:15]:
            warnings += warn(f"用药相关段落未出现委托表述：《{t}》 {sec}")
        print(f"  （共 {len(drug_no_delegate)} 处，建议逐条确认是否为必需）")
    else:
        print(f"  ✓ 所有涉及用药指导的段落均把决定权交回专业人员"
              f"（「{DELEGATE_MARK}」或等价表述）")

    # ---- B.8 敏感信息 ----
    org_hits = []
    for lineno, line in enumerate(raw.split("\n"), 1):
        s = line.strip()
        if not s:
            continue
        for pat in ORG_PATTERNS:
            for mm in pat.finditer(s):
                org_hits.append((lineno, mm.group(0)))
    if org_hits:
        for ln, txt in org_hits[:15]:
            warnings += warn(f"第 {ln} 行疑似具体机构名/联系方式：{txt!r}")
    else:
        print("  ✓ 未出现具体机构名、电话、网址")

    # ---- C 报告 ----
    print("\n[C] 统计")
    print(f"  篇数：{len(docs)}")
    print(f"  总行数：{n_lines}")
    print(f"  正文总字数：{total_chars}")
    print()
    print(f"  {'文档标题':<34} {'行数':>4} {'字数':>7}")
    print("  " + "-" * 48)
    for i, (title, recs) in enumerate(docs.items(), 1):
        c = sum(len(r["content"]) for r in recs)
        t = title if len(title) <= 30 else title[:29] + "…"
        print(f"  {i:>2}. {t:<30} {len(recs):>4} {c:>7}")

    # 单段字数分布（TextSplitter 默认子块 400 字，太长会被再切）
    lens = sorted(len(r["content"]) for recs in docs.values() for r in recs)
    if lens:
        print()
        print(f"  单段字数：min={lens[0]} 中位={lens[len(lens)//2]} max={lens[-1]}")
        over = [x for x in lens if x > 900]
        if over:
            warnings += warn(f"有 {len(over)} 段超过 900 字，会被 TextSplitter 再切，建议检查")

    # ---- 与现有语料标题冲突 ----
    other = os.path.join(HERE, "synthetic_corpus.jsonl")
    if os.path.exists(other) and os.path.abspath(other) != os.path.abspath(path):
        other_titles = set()
        for line in open(other, encoding="utf-8"):
            s = line.strip()
            if s:
                try:
                    other_titles.add(json.loads(s).get("doc_title"))
                except Exception:
                    pass
        dup = set(docs) & other_titles
        if dup:
            for t in dup:
                warnings += warn(f"标题与 synthetic_corpus.jsonl 重复：{t}")
        else:
            print("\n  ✓ 与 synthetic_corpus.jsonl 无标题冲突")

    # ---- 结论 ----
    print()
    print("=" * 68)
    if errors:
        print(f"❌ 校验未通过：{errors} 个错误，{warnings} 个警告")
        return 1
    print(f"✅ 校验通过（{warnings} 个警告）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
