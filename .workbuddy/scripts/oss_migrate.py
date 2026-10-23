#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「数据库里被引用的本地图片」迁移到阿里云 OSS，并改写库里的 URL。

============================================================================
为什么需要这个脚本
============================================================================
切换 psycare.upload.provider=oss 只改变**新上传**的文件去哪；库里已有的
`/api/uploads/...` 相对路径仍然指向本机磁盘。本脚本做三件事：

  1. 从 MySQL 查出真正被引用的图片（banner.image_url / sys_user.avatar_url）；
  2. 把这些文件按**完全相同的相对路径**上传到 OSS（key 不变，便于校验与回滚）；
  3. 生成并（可选）执行 UPDATE，把库里的 URL 换成 OSS 绝对地址。

只迁「被引用的」文件：data/uploads/banner 下存在历史上传后又被删掉的孤儿图，
按目录无脑全传等于把垃圾搬上云。默认头像（RoleAvatar 内置 SVG）不在库里、
也不在任何目录中，天然不参与迁移。

============================================================================
用法
============================================================================
    # ① 体检：只查库、只对文件系统，列出计划与问题，**不做任何修改**
    python .workbuddy/scripts/oss_migrate.py

    # ② 真上传（幂等，可重复跑；key 相同则覆盖）
    python .workbuddy/scripts/oss_migrate.py --upload

    # ③ 上传并改写数据库 URL
    python .workbuddy/scripts/oss_migrate.py --upload --apply-sql

依赖都在隔离 venv 里（requests / pymysql / pyyaml）：
    C:/Users/Lenovo/.workbuddy/binaries/python/envs/default/Scripts/python.exe

配置来源（两处合并读取，与后端保持一致）：
    psycare-server/src/main/resources/application.yml   ← datasource / upload.url-prefix
    application-local.yml                               ← psycare.upload.oss.*（密钥）
"""

import argparse
import base64
import hashlib
import hmac
import mimetypes
import re
import sys
from email.utils import formatdate
from pathlib import Path

import pymysql
import requests
import yaml

# .workbuddy/scripts/xxx.py → parents[0]=scripts, [1]=.workbuddy, [2]=仓库根
ROOT = Path(__file__).resolve().parents[2]
JDBC_RE = re.compile(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)")


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
def load_config():
    app_path = ROOT / "psycare-server" / "src" / "main" / "resources" / "application.yml"
    local_path = ROOT / "application-local.yml"

    app = yaml.safe_load(app_path.read_text(encoding="utf-8"))
    local = {}
    if local_path.exists():
        local = yaml.safe_load(local_path.read_text(encoding="utf-8")) or {}

    # application-local.yml 的键会被合并到 application.yml 之上 ——
    # 与 Spring 的实际优先级相反，但这里只读「不重叠的键」（oss 只在 local 里），
    # 所以合并方向不影响结果。
    psycare = dict(app.get("psycare") or {})
    local_psycare = local.get("psycare") or {}
    if local_psycare.get("upload"):
        merged_upload = dict(psycare.get("upload") or {})
        merged_upload.update(local_psycare["upload"] or {})
        psycare["upload"] = merged_upload

    ds = (app.get("spring") or {}).get("datasource") or {}
    m = JDBC_RE.match(ds.get("url", ""))
    if not m:
        sys.exit("[中止] 无法从 spring.datasource.url 解析出 host/port/db")

    upload = psycare.get("upload") or {}
    oss = upload.get("oss") or {}

    return {
        "db": {
            "host": m.group(1),
            "port": int(m.group(2)),
            "name": m.group(3),
            "user": str(ds.get("username") or "root"),
            # ⚠️ 必须 str()：YAML 里 password: 123456 会被解析成 int，
            #    pymysql 的 sha1_new 要 bytes-like，直接抛
            #    "TypeError: object supporting the buffer API required"，
            #    报错信息完全指不到「密码是数字」这件事。
            "password": str(ds.get("password") or ""),
        },
        "dir": ROOT / (upload.get("dir") or "./data/uploads"),
        "url_prefix": (upload.get("url-prefix") or "/api/uploads").rstrip("/"),
        "oss": {
            "endpoint": (oss.get("endpoint") or "").strip().lstrip("https://").lstrip("http://").strip("/"),
            "bucket": (oss.get("bucket") or "").strip(),
            "domain": (oss.get("public-domain") or "").strip().rstrip("/"),
            "ak": (oss.get("access-key-id") or "").strip(),
            "sk": (oss.get("access-key-secret") or "").strip(),
            "key_prefix": (oss.get("key-prefix") or "").strip().strip("/"),
            "cache": oss.get("cache-max-age-seconds") or 604800,
        },
    }


def check_oss_cfg(oss):
    missing = [k for k in ("endpoint", "bucket", "domain", "ak", "sk") if not oss[k]]
    if missing:
        sys.exit(
            "[中止] application-local.yml 的 psycare.upload.oss 缺少："
            + ", ".join(missing)
            + "\n       填全后重跑（provider 可以先保持 local，迁移不依赖它）。"
        )
    if not oss["domain"].lower().startswith(("http://", "https://")):
        sys.exit("[中止] public-domain 必须带 https:// 前缀，当前：" + oss["domain"])


# ---------------------------------------------------------------------------
# OSS 上传（V1 签名，只用 PUT，不需要引入 SDK）
# ---------------------------------------------------------------------------
def oss_put(session, cfg, key, data, content_type):
    """
    PUT 一个对象。

    ⚠️ 签名细节，改之前先读懂：
      - stringToSign 的 Content-Type 必须与实际请求头**逐字符一致**，不一致报 SignatureDoesNotMatch；
      - CanonicalizedResource 用**未编码**的 key，而 URL 里的 key 必须编码（本项目 key 只有
        字母数字和 /，编码后不变，所以这里没做 quote —— 若将来允许中文名，必须补上）；
      - Cache-Control 不是 x-oss-* 头，不进 CanonicalizedOSSHeaders，所以设置它不影响签名。
    """
    oss = cfg["oss"]
    date = formatdate(usegmt=True)
    resource = f"/{oss['bucket']}/{key}"
    string_to_sign = f"PUT\n\n{content_type}\n{date}\n{resource}"
    sig = base64.b64encode(
        hmac.new(oss["sk"].encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    ).decode()

    url = f"https://{oss['bucket']}.{oss['endpoint']}/{key}"
    return session.put(
        url,
        data=data,
        headers={
            "Date": date,
            "Content-Type": content_type,
            "Authorization": f"OSS {oss['ak']}:{sig}",
            "Cache-Control": f"public, max-age={oss['cache']}",
        },
        timeout=30,
    )


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="把被引用的本地图片迁移到阿里云 OSS")
    ap.add_argument("--upload", action="store_true", help="真正上传（默认只打印计划）")
    ap.add_argument("--apply-sql", action="store_true", help="上传成功后改写数据库 URL")
    args = ap.parse_args()

    cfg = load_config()
    # 体检模式不强制要求 OSS 配置 —— 新人可以先看清「要迁多少」，再去控制台建 bucket。
    if args.upload or args.apply_sql:
        check_oss_cfg(cfg["oss"])
    oss, url_prefix, base_dir = cfg["oss"], cfg["url_prefix"], cfg["dir"]
    domain_label = oss["domain"] or "<public-domain 未配置>"

    if not base_dir.is_dir():
        sys.exit(f"[中止] 本地上传目录不存在：{base_dir}")

    conn = pymysql.connect(
        host=cfg["db"]["host"], port=cfg["db"]["port"], user=cfg["db"]["user"],
        password=cfg["db"]["password"], database=cfg["db"]["name"],
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
    )

    # (表, 主键, URL 列, "WHERE 条件")。banner 连软删的一起迁 ——
    # 软删的 banner 虽然首页不展示，但管理端「回收站」/恢复功能会用到它。
    targets = [
        ("banner", "id", "image_url", "image_url IS NOT NULL AND image_url <> ''"),
        ("sys_user", "id", "avatar_url", "avatar_url IS NOT NULL AND avatar_url <> ''"),
    ]

    plan, skipped, already = [], [], []
    with conn.cursor() as cur:
        for table, pk, col, where in targets:
            cur.execute(f"SELECT {pk} AS id, {col} AS url FROM {table} WHERE {where}")
            for row in cur.fetchall():
                url = (row["url"] or "").strip()
                if not url.startswith(url_prefix + "/"):
                    # 绝对地址 = 已经是云端（或外站）的，不重复迁
                    already.append((table, row["id"], url))
                    continue
                rel = url[len(url_prefix) + 1:]          # banner/2026/10/09/x.png
                src = base_dir / rel
                if not src.is_file():
                    skipped.append((table, row["id"], url, src))
                    continue
                key = f"{oss['key_prefix']}/{rel}" if oss["key_prefix"] else rel
                plan.append({
                    "table": table, "id": row["id"], "rel": rel,
                    "src": src, "key": key, "new_url": f"{domain_label}/{key}",
                })

    print("=" * 78)
    print(f"仓库根     : {ROOT}")
    print(f"本地目录   : {base_dir}")
    print(f"URL 前缀   : {url_prefix}")
    target_desc = (
        f"https://{oss['bucket']}.{oss['endpoint']}"
        if oss["bucket"] and oss["endpoint"] else "<bucket 未配置>"
    )
    if oss["key_prefix"]:
        target_desc += f"/{oss['key_prefix']}"
    print(f"目标       : {target_desc}")
    print("=" * 78)

    print(f"\n待迁移 {len(plan)} 条：")
    for p in plan:
        size = p["src"].stat().st_size
        print(f"  [{p['table']}#{p['id']}] {p['rel']}  ({size/1024:.0f} KB)")
        print(f"        → {p['new_url']}")

    if already:
        print(f"\n已是绝对地址、跳过 {len(already)} 条：")
        for t, i, u in already:
            print(f"  [{t}#{i}] {u[:100]}")

    if skipped:
        print(f"\n⚠️  库里有记录但本地文件已丢失 {len(skipped)} 条（迁完这些记录会 404）：")
        for t, i, u, s in skipped:
            print(f"  [{t}#{i}] {u}")
            print(f"        期望路径：{s}")

    if not plan:
        print("\n没有需要迁移的记录。")
        conn.close()
        return

    if not args.upload:
        print("\n（未加 --upload，以上仅为计划，未做任何修改）")
        print("确认无误后：")
        print("  python .workbuddy/scripts/oss_migrate.py --upload")
        conn.close()
        return

    # ---------------- 上传 ----------------
    print("\n开始上传 …")
    # trust_env=False：不读系统代理。本机 curl 访问本机服务都要 --noproxy '*'，
    # 说明全局代理配置是有的；走代理反而可能连不上 OSS 或签名被中间人改写。
    session = requests.Session()
    session.trust_env = False

    failed = 0
    for idx, p in enumerate(plan, 1):
        ctype = mimetypes.guess_type(p["src"].name)[0] or "application/octet-stream"
        data = p["src"].read_bytes()
        try:
            resp = oss_put(session, cfg, p["key"], data, ctype)
        except requests.RequestException as e:
            print(f"  [{idx}/{len(plan)}] ✗ {p['rel']}  网络异常：{e}")
            failed += 1
            # 第一条就失败通常意味着配置/网络不对，继续跑只会刷屏
            if idx == 1:
                print("\n第一条就失败，已中止。请先核对 endpoint / bucket / 密钥 / 代理。")
                conn.close()
                return
            continue

        if resp.status_code == 200:
            print(f"  [{idx}/{len(plan)}] ✓ {p['key']}  ({len(data)/1024:.0f} KB)")
        else:
            failed += 1
            print(f"  [{idx}/{len(plan)}] ✗ {p['key']}  HTTP {resp.status_code}")
            print(f"        {resp.text[:400]}")

    if failed:
        print(f"\n{failed} 条失败，**不执行数据库改写**。修好重跑即可（已成功的会覆盖，幂等）。")
        conn.close()
        sys.exit(1)

    # ---------------- 生成 SQL ----------------
    sql_lines = [f"UPDATE `{p['table']}` SET `{'image_url' if p['table']=='banner' else 'avatar_url'}`="
                 f"'{p['new_url']}' WHERE id={p['id']};" for p in plan]

    out_dir = ROOT / ".workbuddy" / "tmp"
    out_dir.mkdir(parents=True, exist_ok=True)
    sql_file = out_dir / "oss_migrate_update.sql"
    sql_file.write_text(
        "-- 由 oss_migrate.py 生成：把本地图片 URL 改写为 OSS 绝对地址\n"
        "-- 回滚：把 set 的左边改回原值（脚本已在控制台打印新旧对照）\n"
        + "\n".join(sql_lines) + "\n",
        encoding="utf-8",
    )
    print(f"\nSQL 已写出：{sql_file}")

    if not args.apply_sql:
        print("\n（未加 --apply-sql，数据库未改动）")
        print("确认图片能在浏览器打开后，再执行：")
        print("  python .workbuddy/scripts/oss_migrate.py --upload --apply-sql")
        conn.close()
        return

    # ---------------- 改写数据库 ----------------
    print("\n改写数据库 …")
    try:
        with conn.cursor() as cur:
            for p in plan:
                col = "image_url" if p["table"] == "banner" else "avatar_url"
                cur.execute(f"UPDATE `{p['table']}` SET `{col}`=%s WHERE id=%s",
                            (p["new_url"], p["id"]))
        conn.commit()
        print(f"已更新 {len(plan)} 行。回滚对照：")
        for p in plan:
            print(f"  [{p['table']}#{p['id']}] {url_prefix}/{p['rel']}")
            print(f"      → {p['new_url']}")
    finally:
        conn.close()

    print("\n完成。下一步：")
    print("  1. 把 application-local.yml 的 psycare.upload.provider 改成 oss，重启后端；")
    print("  2. 传一张新 banner + 换一次头像，确认落在 OSS；")
    print("  3. 本地 data/uploads 先别删 —— 它是回退通道，观察一两周再清理。")


if __name__ == "__main__":
    main()
