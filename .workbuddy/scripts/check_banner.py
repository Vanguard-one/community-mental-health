#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
轮播 banner 功能验证（默认只读；带 --run 才做一次真实的增删改）。

回答三个问题：
  1. 表结构与实体对得上吗？（列名 / 类型 / 逻辑删除字段真的存在）
  2. 权限真的在后端吗？（**非管理员**能不能调管理端接口）
  3. 首页过滤真的只看「启用 + 时间窗口内」吗？

第 3 条是本脚本的重点。它必须**绕过接口**直接查 MySQL，因为
「接口没返回某条」有两种可能：过滤逻辑对，或者数据本来就不该返回。
只有把表里的行与接口的返回逐条对上，才算证明。

带 --run 时还会验证**图片比例校验**（竖图必须被拒、容差内必须放行）——
这是「首页不被拉长」的最后一道关口。

用法：
    python check_banner.py                  # 只读：查表结构 + 调只读接口
    python check_banner.py --run            # 额外：比例校验 + 新增→改时间→上移→删除，跑完整闭环
    python check_banner.py --run --keep     # 同上，但保留测试数据（便于前端肉眼验收）

不加 --run 时**不写任何数据**，也不会上传文件。
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

# banner 的目标比例（与 tokens.css 的 --banner-ratio、后端 UploadProperties.targetRatio 同源）
BANNER_RATIO = 2.5


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


def _png_bytes(w, h):
    """
    用标准库生成一张 w×h 的纯色 PNG。

    为什么不用内联 base64 的固定图：比例校验上线后，上传的图必须**比例正确**
    才能通过（2.5:1），而 1×1 是 1:1，会被合法地拒掉。所以这里按需生成任意尺寸。

    实现要点：PNG 的 IDAT 是 zlib 压缩的、每行前面要加一个 filter 字节（0 = None）。
    用 zlib.compress 即可，不需要真的实现 PNG 编码器。
    """
    import struct
    import zlib

    # 每行：1 字节 filter + w 个 RGBA 像素
    raw = b"".join(b"\x00" + b"\x5b\x8f\xd6\xff" * w for _ in range(h))
    comp = zlib.compress(raw, 9)

    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8bit RGBA
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", comp) + chunk(b"IEND", b"")


def _post_multipart(token, path, filename, content, content_type):
    """手工拼 multipart 请求（标准库没有 multipart 支持）。"""
    import uuid

    boundary = "----probe" + uuid.uuid4().hex
    parts = []
    parts.append(("--" + boundary + "\r\n").encode())
    parts.append(
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
    )
    parts.append(f"Content-Type: {content_type}\r\n\r\n".encode())
    parts.append(content)
    parts.append(("\r\n--" + boundary + "--\r\n").encode())
    payload = b"".join(parts)

    req = urllib.request.Request(BASE + path, data=payload, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
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


def upload_png(token, w=1200, h=480):
    """
    上传一张 w×h 的 PNG。

    默认 **1200×480 = 2.5:1**，正好是 banner 的目标比例 —— 这样默认调用就能通过
    比例校验；要测「比例不对必须被拒」时传别的尺寸（如 600×800 竖图）。
    """
    return _post_multipart(token, "/upload/banner", "probe.png", _png_bytes(w, h), "image/png")


def check_schema(cur):
    print("\n【1】表结构核对（直查 information_schema）")
    cur.execute("""
        SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
          FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'banner'
         ORDER BY ORDINAL_POSITION
    """)
    cols = cur.fetchall()
    if not cols:
        print("  [FAIL] banner 表不存在 —— 请先执行 sql/v9_banner_ddl.sql")
        return False
    names = {c[0] for c in cols}
    print(f"  列：{', '.join(c[0] for c in cols)}")
    expected = {"id", "title", "image_url", "link_url", "sort", "status",
                "start_at", "end_at", "is_deleted", "created_at", "updated_at"}
    missing = expected - names
    extra = names - expected
    ok = not missing
    print(f"  [{'PASS' if ok else 'FAIL'}] 列齐全  {'缺: ' + ','.join(missing) if missing else ''}")
    if extra:
        print(f"  [note] 多出的列（无妨）：{','.join(extra)}")

    # is_deleted 是硬约定：没有它，MyBatis-Plus 的 logic-delete-field 就形同虚设
    # （历史上就踩过这个坑 —— 配置写了字段，实体里却没有，delete 变成物理删除）
    has_del = "is_deleted" in names
    print(f"  [{'PASS' if has_del else 'FAIL'}] is_deleted 列存在（逻辑删除的前提）")
    return ok and has_del


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="store_true", help="真的做一次增/改/移/删闭环")
    ap.add_argument("--keep", action="store_true", help="跑完不删测试数据")
    args = ap.parse_args()

    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    print("=" * 64)
    print("轮播 banner 功能验证")
    print("=" * 64)

    schema_ok = check_schema(cur)

    # ---------- 2. 权限 ----------
    print("\n【2】权限校验（后端是否真的断言 ADMIN）")
    perm_ok = True
    resident = login("user01", "user123")
    if resident:
        st, body = call("GET", "/banner/list", token=resident.get("token"))
        denied = st == 200 and body.get("code") == 403
        print(f"  [{'PASS' if denied else 'FAIL'}] 居民读管理端列表 -> "
              f"HTTP {st} code={body.get('code')} {'✓ 已拦下' if denied else '✗ 未拦下（严重！）'}")
        perm_ok = perm_ok and denied
    else:
        print("  [skip] user01 登录失败，跳过居民权限校验")

    doctor = login("doctor_li", "doctor123")
    if doctor:
        st, body = call("POST", "/banner", token=doctor.get("token"),
                        body={"title": "x", "imageUrl": "/x.png"})
        denied = st == 200 and body.get("code") == 403
        print(f"  [{'PASS' if denied else 'FAIL'}] 医生新增 banner   -> "
              f"HTTP {st} code={body.get('code')} {'✓ 已拦下' if denied else '✗ 未拦下（严重！）'}")
        perm_ok = perm_ok and denied
    else:
        print("  [skip] doctor_li 登录失败，跳过医生权限校验")

    admin = login("admin", "admin123")
    if not admin:
        print("\n[X] admin 登录失败，后续步骤无法进行")
        conn.close()
        return 1

    # ---------- 3. 只读比对：表里的行 vs /banner/active 返回 ----------
    print("\n【3】首页过滤核对（表里的行 ↔ /banner/active）")
    st, body = call("GET", "/banner/active", token=admin.get("token"))
    if st != 200 or body.get("code") != 200:
        print(f"  [FAIL] /banner/active 调用失败 HTTP {st} {body}")
        conn.close()
        return 1
    active_ids = {b["id"] for b in (body.get("data") or [])}
    print(f"  接口返回 {len(active_ids)} 条：{sorted(active_ids) if active_ids else '（空）'}")

    # 用同一条 SQL 谓词算出「应该」返回哪些 —— 与 BannerService.active() 逐字对应
    cur.execute("""
        SELECT id, status, start_at, end_at
          FROM banner
         WHERE is_deleted = 0
           AND status = 1
           AND (start_at IS NULL OR start_at <= NOW())
           AND (end_at   IS NULL OR end_at   >= NOW())
         ORDER BY sort, id
    """)
    should_ids = {r[0] for r in cur.fetchall()}
    print(f"  SQL 谓词算出 {len(should_ids)} 条：{sorted(should_ids) if should_ids else '（空）'}")

    filter_ok = active_ids == should_ids
    print(f"  [{'PASS' if filter_ok else 'FAIL'}] 两者一致"
          f"{'' if filter_ok else f'  差异: 接口独有={active_ids - should_ids} SQL独有={should_ids - active_ids}'}")

    # 附带印一下被挡掉的行与原因，便于人工确认过滤是「对的」而不是「碰巧一致」
    cur.execute("SELECT id, title, status, start_at, end_at FROM banner WHERE is_deleted = 0 ORDER BY sort, id")
    allrows = cur.fetchall()
    if allrows:
        print("\n  全表（含被挡掉的）：")
        for r in allrows:
            mark = "展示" if r[0] in active_ids else "不展示"
            print(f"    #{r[0]:<4} {(r[1] or '')[:24]:<26} status={r[2]}  {r[3]} ~ {r[4]}  -> {mark}")

    # ---------- 4. 可选：真实闭环 ----------
    if not args.run:
        print("\n" + "-" * 64)
        print("以上为**只读**检查。加 --run 会真的走一遍：")
        print("  比例校验（竖图被拒 / 容差内放行）→ 上传 → 新增 → 验窗口 → 上移 → 删除。")
        conn.close()
        return 0 if (schema_ok and perm_ok and filter_ok) else 1

    print("\n【4】真实闭环（上传 → 新增 → 时间窗口 → 上移 → 删除）")
    created_ids = []
    all_ok = schema_ok and perm_ok and filter_ok

    # 4.0 比例校验（**负向测试**）：比例明显不对的图必须被后端拒掉。
    # 这条单独测，是因为它是「首页不被拉长」的最后一道关口 ——
    # 前端 canvas 裁剪只是体验优化，绕过前端直接打接口时只有这里能兜住。
    st, body = upload_png(admin.get("token"), 600, 800)  # 0.75:1 竖图，远超 10% 容差
    ratio_ok = st == 200 and body.get("code") == 400
    msg = (body.get("message") or body.get("msg") or "") if isinstance(body, dict) else ""
    print(f"  [{'PASS' if ratio_ok else 'FAIL'}] 竖图 600×800 被比例校验拦下 -> HTTP {st} {msg[:60]}")
    all_ok = all_ok and ratio_ok

    # 4.0b 容差内必须放行：2.4:1 与目标 2.5:1 差 4%，应当通过
    st, body = upload_png(admin.get("token"), 1200, 500)  # 2.4:1
    tol_ok = st == 200 and body.get("code") == 200
    print(f"  [{'PASS' if tol_ok else 'FAIL'}] 2.4:1（差 4%，容差内）放行 -> HTTP {st} code={body.get('code')}")
    all_ok = all_ok and tol_ok

    # 4.1 上传（标准 1200×480）
    st, body = upload_png(admin.get("token"))
    img_ok = st == 200 and body.get("code") == 200 and (body.get("data") or {}).get("url")
    print(f"  [{'PASS' if img_ok else 'FAIL'}] 上传 1200×480 图片 -> HTTP {st} "
          f"{(body.get('data') or {}).get('url') or body}")
    all_ok = all_ok and bool(img_ok)
    if not img_ok:
        conn.close()
        return 1
    img_url = body["data"]["url"]

    # 4.2 新增：一条现在生效的
    st, body = call("POST", "/banner", token=admin.get("token"), body={
        "title": "探针-立即展示", "imageUrl": img_url, "linkUrl": "/scales", "status": 1,
    })
    ok = st == 200 and body.get("code") == 200
    print(f"  [{'PASS' if ok else 'FAIL'}] 新增（立即生效）-> {json.dumps(body.get('data'), ensure_ascii=False)[:120]}")
    all_ok = all_ok and ok
    if ok:
        created_ids.append(body["data"]["id"])
        id_now = body["data"]["id"]
        st2, b2 = call("GET", "/banner/active", token=admin.get("token"))
        shown = id_now in {x["id"] for x in (b2.get("data") or [])}
        print(f"  [{'PASS' if shown else 'FAIL'}] 立即生效的能出现在 /banner/active")
        all_ok = all_ok and shown

    # 4.3 新增：一条未来才生效的（**必须不出现**）
    st, body = call("POST", "/banner", token=admin.get("token"), body={
        "title": "探针-未来展示", "imageUrl": img_url, "status": 1,
        "startAt": "2099-01-01 00:00:00",
    })
    ok = st == 200 and body.get("code") == 200
    if ok:
        created_ids.append(body["data"]["id"])
        id_future = body["data"]["id"]
        st2, b2 = call("GET", "/banner/active", token=admin.get("token"))
        hidden = id_future not in {x["id"] for x in (b2.get("data") or [])}
        print(f"  [{'PASS' if hidden else 'FAIL'}] 开始时间在未来 → 不出现在首页（这是最易漏的一条）")
        all_ok = all_ok and hidden
    else:
        print(f"  [FAIL] 新增（未来生效）失败 -> {body}")
        all_ok = False

    # 4.4 新增：一条已过期的（**必须不出现**）
    st, body = call("POST", "/banner", token=admin.get("token"), body={
        "title": "探针-已过期", "imageUrl": img_url, "status": 1,
        "endAt": "2000-01-01 00:00:00",
    })
    if st == 200 and body.get("code") == 200:
        created_ids.append(body["data"]["id"])
        id_past = body["data"]["id"]
        st2, b2 = call("GET", "/banner/active", token=admin.get("token"))
        hidden = id_past not in {x["id"] for x in (b2.get("data") or [])}
        print(f"  [{'PASS' if hidden else 'FAIL'}] 结束时间已过 → 不出现在首页")
        all_ok = all_ok and hidden
    else:
        # 后端对「结束早于开始」会拒绝，但这里是单端设过去时间，应当允许
        print(f"  [FAIL] 新增（已过期）失败 -> {body}")
        all_ok = False

    # 4.5 停用（**必须不出现**）
    if created_ids:
        target = created_ids[0]
        st, body = call("PUT", f"/banner/{target}/status", token=admin.get("token"))
        ok = st == 200 and body.get("code") == 200 and body["data"]["status"] == 0
        if ok:
            st2, b2 = call("GET", "/banner/active", token=admin.get("token"))
            hidden = target not in {x["id"] for x in (b2.get("data") or [])}
            print(f"  [{'PASS' if hidden else 'FAIL'}] 停用后 → 不出现在首页（启停开关真的生效）")
            all_ok = all_ok and hidden
        else:
            print(f"  [FAIL] 停用失败 -> {body}")
            all_ok = False

    # 4.6 上移：返回的必须是**重排后的完整列表**（前端直接替换）
    st, body = call("PUT", f"/banner/{created_ids[-1]}/move?direction=up", token=admin.get("token"))
    ok = st == 200 and body.get("code") == 200 and isinstance(body.get("data"), list)
    print(f"  [{'PASS' if ok else 'FAIL'}] 上移返回完整列表（{len(body.get('data') or [])} 条）")
    all_ok = all_ok and ok

    # 4.7 删除
    for i in created_ids:
        st, body = call("DELETE", f"/banner/{i}", token=admin.get("token"))
        ok = st == 200 and body.get("code") == 200
        print(f"  [{'PASS' if ok else 'FAIL'}] 删除 #{i}")
        all_ok = all_ok and ok

    # 4.8 删除后必须查不到（逻辑删除真正生效）
    if created_ids:
        cur.execute(
            "SELECT COUNT(*) FROM banner WHERE id IN (%s) AND is_deleted = 0"
            % ",".join(str(i) for i in created_ids))
        n = cur.fetchone()[0]
        print(f"  [{'PASS' if n == 0 else 'FAIL'}] 删除后 is_deleted=0 的行数为 0（逻辑删除生效，实际={n}）")
        all_ok = all_ok and (n == 0)

    print("\n" + "=" * 64)
    print("✅ 全部通过。" if all_ok else "❌ 有断言未通过，见上方 FAIL 行。")
    conn.close()
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
