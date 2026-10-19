#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
PsyCare 后端全链路接口验证脚本（Phase 5 联调，替代人工点页面）

覆盖：
  1. 登录三端账号（居民 / 医生 / 管理员）
  2. 量表列表 + 量表详情
  3. 提交 PHQ-9（第 9 题阳性 → 验证危机短路）
  4. 测评历史
  5. AI 问诊开会话 + SSE 流式（无 LLM_API_KEY 时验证降级表现）
  6. 医生推荐（验证会话关联测评驱动）
  7. 居民越权访问 /alert/page、/admin/dashboard/* → 应 403
  8. 工单状态机全流转 PENDING→CONFIRMED→PROCESSING→FOLLOWED→CLOSED + 流转日志落库
  9. 驾驶舱 4 个统计接口

用法：python e2e_api_check.py
"""
import json
import urllib.error
import urllib.request

BASE = "http://localhost:18989/api"
PASS = "PASS"
FAIL = "FAIL"

results = []


def record(ok, label, detail=""):
    tag = PASS if ok else FAIL
    results.append((ok, label))
    print(f"  [{tag}] {label}" + (f"  -> {detail}" if detail else ""))
    return ok


def call(method, path, token=None, body=None, raw=False):
    """返回 (http_status, payload)。payload 为 parsed dict 或原始文本。"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        status = e.code
    except Exception as e:
        return 0, {"_error": str(e)}
    if raw:
        return status, text
    try:
        return status, json.loads(text)
    except Exception:
        return status, {"_raw": text[:400]}


def login(username, password):
    st, body = call("POST", "/auth/login", body={"username": username, "password": password})
    if st == 200 and isinstance(body, dict) and body.get("code") == 200:
        return body["data"]
    return None


print("=" * 72)
print("PsyCare 后端全链路接口验证")
print("=" * 72)

# ---------- 1. 登录 ----------
print("\n[1] 登录三端账号")
resident = login("user01", "user123")
record(bool(resident), "居民 user01 登录", f"role={resident and resident.get('role')}")
doctor = login("doctor_li", "doctor123")
record(bool(doctor), "医生 doctor_li 登录", f"role={doctor and doctor.get('role')}")
admin = login("admin", "admin123")
record(bool(admin), "管理员 admin 登录", f"role={admin and admin.get('role')}")

if not (resident and doctor and admin):
    print("\n登录失败，后续步骤无法继续。")
    raise SystemExit(1)

rt, dt, at = resident["token"], doctor["token"], admin["token"]
resident_id = resident["userId"]

# ---------- 2. 量表 ----------
print("\n[2] 量表列表与详情")
st, body = call("GET", "/scale/list", rt)
scales = body.get("data") if isinstance(body, dict) else None
record(st == 200 and bool(scales), "/scale/list 返回量表", f"{len(scales or [])} 个")
phq9 = next((s for s in (scales or []) if s.get("code") == "PHQ9"), None)
record(phq9 is not None and phq9.get("itemCount") == 9,
       "列表含 PHQ9 且 itemCount=9", f"itemCount={phq9 and phq9.get('itemCount')}")

st, body = call("GET", "/scale/1/detail", rt)
items = (body.get("data") or {}).get("items") if isinstance(body, dict) else None
record(st == 200 and bool(items), "/scale/1/detail 返回题目", f"{len(items or [])} 题")

# ---------- 3. 提交测评（第 9 题阳性 → 危机短路） ----------
print("\n[3] 提交 PHQ-9（第 9 题选阳性，验证危机短路）")
answers = []
for it in items or []:
    opts = it.get("options") or []
    if not opts:
        continue
    if it.get("itemNo") == 9:
        chosen = max(opts, key=lambda o: o.get("score", 0))  # 阳性 = 最高分
    else:
        chosen = min(opts, key=lambda o: o.get("score", 0))  # 其余全 0 分
    answers.append({"itemId": it["id"], "optionId": chosen["id"]})

st, body = call("POST", "/assessment/submit", rt,
                {"scaleId": 1, "answers": answers})
result = body.get("data") if isinstance(body, dict) else None
record(st == 200 and bool(result), "/assessment/submit 提交成功",
       f"HTTP {st} totalScore={result and result.get('totalScore')}")
if result:
    print("      Result 返回体：", json.dumps(result, ensure_ascii=False)[:400])
    crisis_level = result.get("riskLevel")
    record(crisis_level == "D", "第 9 题阳性触发危机短路 → riskLevel=D",
           f"riskLevel={crisis_level}")
assessment_id = result.get("assessmentId") if result else None
print(f"      assessmentId = {assessment_id}")

# ---------- 4. 测评历史 ----------
print("\n[4] 测评历史")
st, body = call("GET", "/assessment/history", rt)
hist = body.get("data") if isinstance(body, dict) else None
record(st == 200 and isinstance(hist, list) and len(hist) > 0,
       "/assessment/history 返回列表", f"{len(hist or [])} 条")

# ---------- 5. AI 问诊 ----------
print("\n[5] AI 问诊（开会话 + SSE 流式）")
st, body = call("POST", "/ai-consult/open", rt, {"assessmentId": assessment_id})
opened = body.get("data") if isinstance(body, dict) else None
record(st == 200 and isinstance(body, dict) and body.get("code") == 200 and opened,
       "/ai-consult/open 返回 Result 包装（R-2 回归）",
       f"payload={json.dumps(body, ensure_ascii=False)[:120]}")
session_id = opened.get("sessionId") if opened else None
print(f"      sessionId = {session_id}")

if session_id:
    st, text = call("POST", "/ai-consult/chat", rt,
                    {"sessionId": session_id, "content": "我不想活了，想要结束自己的生命"},
                    raw=True)
    print(f"      SSE HTTP {st}，首 300 字符：{text[:300]!r}")
    got_event = "data:" in text or st == 200
    record(got_event, "/ai-consult/chat 建立 SSE 流",
           "已建立（正文依赖 LLM_API_KEY，未配置时为空/报错属预期）")

# ---------- 6. 医生推荐 ----------
print("\n[6] 医生推荐（验证会话关联测评驱动）")
st, body = call("GET", f"/recommend/doctors?sessionId={session_id}&topN=3", rt)
docs = body.get("data") if isinstance(body, dict) else None
record(st == 200 and isinstance(docs, list), "/recommend/doctors 返回推荐",
       f"{len(docs or [])} 位")
if docs:
    print("      推荐理由：")
    for d in docs:
        print(f"        - {d.get('doctorName', d.get('name'))}: {d.get('reason')}")

# ---------- 7. 越权校验 ----------
# 注意：本项目业务错误码走 HTTP 200 + body.code，不要用 HTTP 状态码断言业务结果。
print("\n[7] 授权校验（居民账号访问管理端接口应 403）")
st, body = call("GET", "/alert/page", rt)
record(st == 200 and (body or {}).get("code") == 403,
       "居民访问 /alert/page → 业务码 403",
       f"HTTP {st} code={(body or {}).get('code')} msg={(body or {}).get('msg')}")
st, body = call("GET", "/admin/dashboard/stats", rt)
record(st == 200 and (body or {}).get("code") == 403,
       "居民访问 /admin/dashboard/stats → 业务码 403",
       f"HTTP {st} code={(body or {}).get('code')}")
st, body = call("GET", "/alert/page", at)
record(st == 200 and (body or {}).get("code") == 200,
       "管理员访问 /alert/page → 业务码 200",
       f"HTTP {st} code={(body or {}).get('code')}")

# ---------- 8. 工单状态机全流转 ----------
print("\n[8] 工单状态机全流转（PENDING → CONFIRMED → PROCESSING → FOLLOWED → CLOSED）")
st, body = call("GET", "/alert/page?current=1&size=50", dt)
page = body.get("data") if isinstance(body, dict) else None
records = (page or {}).get("records") or []
record(st == 200 and isinstance(page, dict), "/alert/page 返回分页结构",
       f"total={page and page.get('total')} records={len(records)}")

crisis = next((r for r in records if r.get("alertType") == "CRISIS"), None)
record(crisis is not None, "存在 CRISIS 类型工单（AI 危机词触发）",
       f"alertId={crisis and crisis.get('id')} status={crisis and crisis.get('status')}")

st, body = call("GET", "/alert/status-count", dt)
counts = body.get("data") if isinstance(body, dict) else None
record(st == 200 and isinstance(counts, list) and len(counts) == 6,
       "/alert/status-count 固定返回 6 个状态", f"{len(counts or [])} 个")

# 选一个 PENDING 工单走完整状态机
target = next((r for r in records if r.get("status") == "PENDING"), crisis)
if target:
    aid = target["id"]
    st, body = call("GET", f"/alert/{aid}", dt)
    detail = body.get("data") if isinstance(body, dict) else None
    record(st == 200 and bool(detail), f"GET /alert/{aid} 详情（深链可达）",
           f"status={(detail or {}).get('status')}")

    st, body = call("GET", f"/alert/{aid}/logs", dt)
    before = body.get("data") if isinstance(body, dict) else []
    record(st == 200, f"GET /alert/{aid}/logs 流转日志", f"{len(before or [])} 条")

    chain = ["CONFIRMED", "PROCESSING", "FOLLOWED", "CLOSED"]
    for to_status in chain:
        st, body = call("POST", f"/alert/{aid}/handle", dt,
                        {"toStatus": to_status, "note": f"E2E 自动流转 -> {to_status}"})
        ok = st == 200 and isinstance(body, dict) and body.get("code") == 200
        record(ok, f"流转 → {to_status}", f"HTTP {st} msg={(body or {}).get('msg')}")

    # 非法流转应被拒
    st, body = call("POST", f"/alert/{aid}/handle", dt,
                    {"toStatus": "PENDING", "note": "非法回退"})
    record(isinstance(body, dict) and body.get("code") not in (200, None),
           "非法流转 CLOSED→PENDING 被拒绝", f"code={(body or {}).get('code')}")

    st, body = call("GET", f"/alert/{aid}", dt)
    final = body.get("data") if isinstance(body, dict) else {}
    final_status = (final or {}).get("status")
    record(final_status == "CLOSED", "工单终态为 CLOSED", f"status={final_status}")
    record((final or {}).get("handleNote") == "E2E 自动流转 -> CLOSED",
           "处置说明落在 alert_record.handle_note",
           f"handleNote={(final or {}).get('handleNote')!r}")

    st, body = call("GET", f"/alert/{aid}/logs", dt)
    after = body.get("data") if isinstance(body, dict) else []
    record(len(after or []) > len(before or []),
           "流转日志逐条落库", f"{len(before or [])} → {len(after or [])} 条")
    for lg in (after or [])[-5:]:
        print(f"        - {lg.get('fromStatus')} → {lg.get('toStatus')} "
              f"by={lg.get('operatorId')} remark={lg.get('remark')!r}")
else:
    record(False, "未找到可流转工单")

# ---------- 9. 驾驶舱 ----------
print("\n[9] 管理端驾驶舱 4 接口")
for path, label in [("/admin/dashboard/stats", "stats"),
                    ("/admin/dashboard/trend", "trend"),
                    ("/admin/dashboard/risk-dist", "risk-dist"),
                    ("/admin/dashboard/alert-sum", "alert-sum")]:
    st, body = call("GET", path, at)
    payload = body.get("data") if isinstance(body, dict) else None
    record(st == 200 and payload is not None, f"GET {path}",
           json.dumps(payload, ensure_ascii=False)[:200])

# ---------- 汇总 ----------
print("\n" + "=" * 72)
passed = sum(1 for ok, _ in results if ok)
failed = [(l, ) for ok, l in results if not ok]
print(f"合计 {len(results)} 项：{passed} 通过，{len(results) - passed} 失败")
if failed:
    print("失败项：")
    for (l,) in failed:
        print("  - " + l)
print("=" * 72)
