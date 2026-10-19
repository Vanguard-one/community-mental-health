#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
驾驶舱数字 × 真实库值交叉核对（Phase 5 验收项：数字与实际库值一致）

用直连 MySQL 的聚合结果，逐项比对 /admin/dashboard/* 四个接口的返回，
防止 AdminDashboardMapper.xml 的聚合 SQL（尤其是按天分组的 trend）写歪。

用法：python e2e_dashboard_db_check.py
"""
import json
import urllib.request

import pymysql

BASE = "http://localhost:18989/api"
DB = dict(host="localhost", port=3306, user="root", password="123456",
          database="psycare", charset="utf8mb4")

ok_all = True


def api(path, token):
    req = urllib.request.Request(BASE + path, method="GET")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))["data"]


def login(u, p):
    data = json.dumps({"username": u, "password": p}).encode()
    req = urllib.request.Request(BASE + "/auth/login", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))["data"]["token"]


def check(label, api_val, db_val):
    global ok_all
    ok = str(api_val) == str(db_val)
    ok_all = ok_all and ok
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}: 接口={api_val}  库值={db_val}")
    return ok


token = login("admin", "admin123")
conn = pymysql.connect(**DB, cursorclass=pymysql.cursors.DictCursor)


def scalar(sql, args=None):
    with conn.cursor() as c:
        c.execute(sql, args or ())
        row = c.fetchone()
        return list(row.values())[0] if row else None


def rows(sql, args=None):
    with conn.cursor() as c:
        c.execute(sql, args or ())
        return c.fetchall()


print("=" * 72)
print("驾驶舱数字 × 真实库值交叉核对")
print("=" * 72)

print("\n[顶部 4 卡片]")
stats = api("/admin/dashboard/stats", token)
check("服务居民数 (role=RESIDENT)", stats["userCount"],
      scalar("SELECT COUNT(*) FROM sys_user WHERE role='RESIDENT'"))
check("测评总数", stats["assessmentCount"], scalar("SELECT COUNT(*) FROM assessment"))
check("待处置工单 (PENDING)", stats["pendingAlertCount"],
      scalar("SELECT COUNT(*) FROM alert_record WHERE status='PENDING'"))
check("今日新增测评", stats["todayNew"],
      scalar("SELECT COUNT(*) FROM assessment WHERE created_at >= CURDATE()"))

print("\n[风险分布环形图]")
risk_api = {r["riskLevel"]: r["count"] for r in api("/admin/dashboard/risk-dist", token)}
risk_db = {r["risk_level"]: r["c"] for r in rows(
    "SELECT risk_level, COUNT(*) c FROM assessment GROUP BY risk_level")}
for lv in ["A", "B", "C", "D"]:
    check(f"风险等级 {lv}", risk_api.get(lv, 0), risk_db.get(lv, 0))

print("\n[工单状态柱状图]")
sum_api = {r["status"]: r["count"] for r in api("/admin/dashboard/alert-sum", token)}
sum_db = {r["status"]: r["c"] for r in rows(
    "SELECT status, COUNT(*) c FROM alert_record GROUP BY status")}
for s in ["PENDING", "CONFIRMED", "PROCESSING", "FOLLOWED", "CLOSED", "REJECTED"]:
    check(f"工单状态 {s}", sum_api.get(s, 0), sum_db.get(s, 0))

print("\n[趋势折线图（近 30 天按天）]")
trend_api = api("/admin/dashboard/trend", token)
trend_db = {str(r["d"]): (r["c"], r["avg"]) for r in rows(
    "SELECT DATE(created_at) d, COUNT(*) c, AVG(total_score) avg "
    "FROM assessment GROUP BY DATE(created_at) ORDER BY d")}
bad = []
for pt in trend_api:
    d = pt["date"]
    exp_c, exp_avg = trend_db.get(d, (0, None))
    got_c = pt["count"]
    got_avg = pt["avgScore"]
    avg_ok = (exp_avg is None and got_avg is None) or (
        exp_avg is not None and got_avg is not None
        and abs(float(exp_avg) - float(got_avg)) < 0.05)
    if got_c != exp_c or not avg_ok:
        bad.append((d, got_c, exp_c, got_avg, exp_avg))
print(f"  接口返回 {len(trend_api)} 个数据点，与库中 {len(trend_db)} 个有数据日期核对")
non_zero = [p for p in trend_api if p["count"] > 0]
print(f"  其中非零数据点 {len(non_zero)} 个：")
for p in non_zero:
    print(f"    - {p['date']}: count={p['count']} avgScore={p['avgScore']}")
print(f"  该窗口外仍有测评的日期（正常，trend 只取近 30 天）："
      f"{sorted(set(trend_db) - set(p['date'] for p in trend_api))}")
if bad:
    global_ok = False
    ok_all = False
    print(f"  [FAIL] 以下数据点不一致：{bad}")
else:
    print("  [PASS] 窗口内所有数据点的 count 与 avgScore 全部一致")

conn.close()
print("\n" + "=" * 72)
print("结论：" + ("驾驶舱数字与真实库值完全一致" if ok_all else "存在不一致，见上方 FAIL 项"))
print("=" * 72)
