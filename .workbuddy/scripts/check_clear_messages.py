#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
清空会话功能验证（只读探测 + 一次真实调用）。

回答一个问题：「清空记录」到底有没有做到「删消息、留记忆」。

判定必须同时满足三条：
  1. `consult_message` 里该会话的行数归零；
  2. `consult_session.summary` **原样保留**（这是「上下文记忆」的唯一载体）；
  3. 接口返回的 deleted 等于清空前的行数（说明真的作用在了这条会话上）。

为什么不用接口的返回体自证：那只能说明「后端说它删了」。
必须回表看 MySQL 的实际行，才算证明（与硬约定 #70 同一精神：
表结构/数据一律实测，不凭代码推断）。

用法：
    python check_clear_messages.py                 # 只查验当前状态，不写任何数据
    python check_clear_messages.py --session 65    # 指定会话（默认挑消息最多的那条）
    python check_clear_messages.py --run           # 真的调一次清空接口（会删数据！）
    python check_clear_messages.py --run --session 65

不加 --run 时**不做任何写操作**，只打印当前状态与将要发生什么。
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

import pymysql

BASE = "http://localhost:18989/api"
DB = dict(host="127.0.0.1", port=3306, user="root", password="123456",
          database="psycare", charset="utf8mb4")


def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"_raw": raw[:400]}
    except Exception as e:
        return 0, {"_error": str(e)}


def login(username, password):
    st, body = call("POST", "/auth/login", body={"username": username, "password": password})
    if st == 200 and isinstance(body, dict) and body.get("code") == 200:
        return body["data"]
    return None


def snapshot(cur, session_id):
    """取该会话的 (消息行数, 摘要, 摘要字数)。判据全部来自真实表。"""
    cur.execute("SELECT COUNT(*) FROM consult_message WHERE session_id = %s", (session_id,))
    msg_count = cur.fetchone()[0]
    cur.execute("SELECT summary FROM consult_session WHERE id = %s", (session_id,))
    row = cur.fetchone()
    summary = row[0] if row else None
    return msg_count, summary, len(summary or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", type=int, default=None, help="会话 id，默认挑消息最多的一条")
    ap.add_argument("--run", action="store_true", help="真的调用清空接口（会删数据）")
    ap.add_argument("--user", default="user01", help="登录账号（默认 user01）")
    ap.add_argument("--password", default="user123")
    args = ap.parse_args()

    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    # 挑测试对象：优先带摘要的（才能验证「记忆保留」这一半），其次消息最多的
    if args.session is None:
        cur.execute("""
            SELECT s.id,
                   (s.summary IS NOT NULL AND s.summary <> '') AS has_summary,
                   COUNT(m.id) AS cnt
            FROM consult_session s LEFT JOIN consult_message m ON m.session_id = s.id
            GROUP BY s.id, has_summary
            HAVING cnt > 0
            ORDER BY has_summary DESC, cnt DESC LIMIT 1
        """)
        row = cur.fetchone()
        if not row:
            print("库里没有任何带消息的会话，无法测试。")
            return 1
        session_id = row[0]
        print(f"自动选中会话 #{session_id}（有摘要={bool(row[1])} 消息数={row[2]}）")
    else:
        session_id = args.session

    print("\n" + "=" * 64)
    print(f"清空会话功能验证 · session={session_id}")
    print("=" * 64)

    cur.execute("SELECT resident_id, status, summary FROM consult_session WHERE id = %s", (session_id,))
    meta = cur.fetchone()
    if not meta:
        print(f"[X] 会话 #{session_id} 不存在")
        return 1
    print(f"归属居民 resident_id={meta[0]}  状态={meta[1]}")

    before_count, before_summary, before_len = snapshot(cur, session_id)
    print(f"\n【清空前】消息 {before_count} 条 · 摘要 {before_len} 字")
    if before_summary:
        print(f"           摘要前 60 字：{before_summary[:60]}…")

    # ---- 归属校验：换个账号调应被 403 拦下（这是最该守住的一条） ----
    other = login("user02", "user123") or login("user03", "user123")
    if other and other.get("userId") != meta[0]:
        st, body = call("POST", f"/ai-consult/{session_id}/clear-messages",
                        token=other.get("token"))
        ok = st == 200 and body.get("code") == 403
        print(f"\n[归属校验] 他人账号调清空 -> HTTP {st} code={body.get('code')} "
              f"{'✓ 已拦下' if ok else '✗ 未拦下（严重！）'}")
    else:
        print("\n[归属校验] 跳过：没找到归属不同居民的测试账号")

    if not args.run:
        print("\n" + "-" * 64)
        print("这是**只读**预演。加上 --run 才会真的调接口清空。")
        print(f"届时预期：消息 {before_count} -> 0；摘要 {before_len} 字 -> 保持 {before_len} 字")
        conn.close()
        return 0

    # ---- 真调 ----
    user = login(args.user, args.password)
    if not user:
        print(f"[X] 登录失败（{args.user}），无法调用接口")
        conn.close()
        return 1
    if user.get("userId") != meta[0]:
        print(f"[!] {args.user}(userId={user.get('userId')}) 不是该会话的归属居民"
              f"(resident_id={meta[0]}) —— 接口应返回 403，这也是一种验证。")

    st, body = call("POST", f"/ai-consult/{session_id}/clear-messages", token=user.get("token"))
    print(f"\n【调用接口】HTTP {st}  body={json.dumps(body, ensure_ascii=False)[:200]}")
    reported = (body.get("data") or {}).get("deleted") if isinstance(body, dict) else None

    after_count, after_summary, after_len = snapshot(cur, session_id)
    print(f"\n【清空后】消息 {after_count} 条 · 摘要 {after_len} 字")

    checks = [
        ("消息已清空", after_count == 0, f"{before_count} -> {after_count}"),
        ("删除条数如实返回", reported == before_count, f"接口报 {reported}，实际前值 {before_count}"),
        ("上下文摘要保留", after_len == before_len and after_summary == before_summary,
         f"{before_len} 字 -> {after_len} 字"),
    ]
    print()
    all_ok = True
    for name, ok, detail in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}  ({detail})")
        all_ok = all_ok and ok
    print("\n" + ("✅ 全部通过：记录已清空，记忆仍在。" if all_ok
                  else "❌ 有断言未通过，见上方 FAIL 行。"))
    conn.close()
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
