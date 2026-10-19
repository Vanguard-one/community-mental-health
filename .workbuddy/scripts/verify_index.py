#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
MySQL ↔ Milvus 索引一致性校验（Phase 3 · 阶段五的验收工具）。

回答一个具体问题：**「MySQL 里的子块」与「Milvus 里的向量」是不是同一批**。

为什么要单独一个脚本
--------------------
两个库之间没有事务，写入是「先 MySQL 后 Milvus」的两步。任何一步出问题
（Milvus 挂了、向量化有几条毒数据、有人手工 drop 了 collection），
现象都是「界面上看着正常，检索结果不对」——**没有任何报错**。
这个脚本是唯一能把它变成一行明确结论的东西。

比对三层，逐层收窄
------------------
1. **条数**：MySQL 子块总数 vs Milvus 集合行数。不等即说明有整篇没写进去。
2. **id 集合**：逐文档比对 `kb_chunk.id` 的集合。条数相等但集合不同，
   说明有 A 文档的向量写到了别处（或旧行没清干净）。
3. **正文抽样**：抽若干块，比对 MySQL `content` 与 Milvus `text` 是否同源。
   ⚠️ Milvus 的 text 字段有字节上限（写入前会截断），所以只对**未达上限**的块断言相等，
   超长块退化为「前缀一致」检查。

⚠️ 不要用「向量相等 / MD5 相等」当判据
--------------------------------------
已实测（2026-10-08）：同一文本**放进批里 vs 单独调用**会得到 cos≈0.998 的两个版本，
服务端不可逐位复现。所以本脚本**从不比较向量**，只比对「条数 / id / 正文」。

用法
----
  # 默认连本机 psycare 库 + http://localhost:19530
  python verify_index.py

  # 只校验某一篇
  python verify_index.py --doc 3

  # 不连 Milvus，只看 MySQL 侧统计
  python verify_index.py --mysql-only

退出码：0 一致 / 1 不一致 / 2 连不上
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

try:
    import pymysql
except ImportError:
    sys.exit("缺少 pymysql。请用隔离环境的解释器运行：\n"
             "  C:/Users/Lenovo/.workbuddy/binaries/python/envs/default/Scripts/python.exe "
             + os.path.basename(__file__))

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
MILVUS_TEXT_MAX_BYTES = 4096      # 与 MilvusProperties.textMaxBytes 保持一致


# ==================== MySQL ====================

def mysql_conn(args):
    return pymysql.connect(
        host=args.host, port=args.port, user=args.user, password=args.password,
        database=args.db, charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
    )


def fetch_mysql(conn, only_doc=None):
    docs = {}
    with conn.cursor() as cur:
        sql = ("SELECT id, title, status, chunk_count, embed_model, dim, error_msg "
               "FROM kb_document WHERE is_deleted = 0")
        params = []
        if only_doc:
            sql += " AND id = %s"
            params.append(only_doc)
        cur.execute(sql, params)
        for row in cur.fetchall():
            row["chunks"] = []
            docs[row["id"]] = row

        if docs:
            ids = ",".join(str(int(d)) for d in docs.keys())   # 键来自上面的查询，非用户输入
            cur.execute(
                "SELECT id, doc_id, chunk_type, ordinal, content, content_hash "
                "FROM kb_chunk WHERE is_deleted = 0 AND chunk_type = 'CHILD' AND doc_id IN (%s) "
                "ORDER BY doc_id, ordinal" % ids)
            for row in cur.fetchall():
                docs[row["doc_id"]]["chunks"].append(row)
    return docs


# ==================== Milvus（REST v2，零依赖） ====================

def milvus_post(args, path, payload):
    """Milvus REST **失败也走 HTTP 200**，必须解析 body 里的 code（硬约定 #2 同形）"""
    req = urllib.request.Request(
        args.milvus.rstrip("/") + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with OPENER.open(req, timeout=args.timeout) as r:
            body = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return None, "HTTP %s: %s" % (e.code, e.read().decode("utf-8", "replace")[:160])
    except Exception as e:                                  # noqa: BLE001
        return None, str(e)

    code = body.get("code")
    if code not in (0, 200, None):
        return None, "code=%s message=%s" % (code, body.get("message"))
    return body.get("data"), None


def milvus_row_count(args):
    data, err = milvus_post(args, "/v2/vectordb/collections/get_stats",
                            {"dbName": args.milvus_db, "collectionName": args.collection})
    if err:
        return -1, err
    if isinstance(data, dict):
        n = data.get("rowCount", data.get("row_count"))
        return (int(n) if n is not None else 0), None
    return 0, None


def milvus_chunk_ids(args, doc_id):
    out = []
    offset = 0
    while True:
        data, err = milvus_post(args, "/v2/vectordb/entities/query", {
            "dbName": args.milvus_db,
            "collectionName": args.collection,
            "filter": "doc_id == %d" % doc_id,
            "outputFields": ["chunk_id"],
            "limit": 1000,
            "offset": offset,
        })
        if err:
            return None, err
        if not data:
            break
        out.extend(int(r["chunk_id"]) for r in data)
        if len(data) < 1000:
            break
        offset += len(data)
    return sorted(out), None


def milvus_texts(args, doc_id):
    """取回 text 字段，供正文抽样比对"""
    data, err = milvus_post(args, "/v2/vectordb/entities/query", {
        "dbName": args.milvus_db,
        "collectionName": args.collection,
        "filter": "doc_id == %d" % doc_id,
        "outputFields": ["chunk_id", "text"],
        "limit": 16384,
    })
    if err:
        return {}, err
    return {int(r["chunk_id"]): r.get("text", "") for r in (data or [])}, None


# ==================== 主流程 ====================

def md5(s):
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def main():
    ap = argparse.ArgumentParser(description="校验 MySQL 与 Milvus 的索引一致性")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=3306)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default="123456")
    ap.add_argument("--db", default="psycare")
    ap.add_argument("--milvus", default="http://localhost:19530")
    ap.add_argument("--milvus-db", default="psycare")
    ap.add_argument("--collection", default="psycare_kb_chunk")
    ap.add_argument("--doc", type=int, help="只校验某一篇文档")
    ap.add_argument("--mysql-only", action="store_true")
    ap.add_argument("--sample", type=int, default=5, help="每篇抽样比对正文的块数")
    ap.add_argument("--timeout", type=int, default=20)
    args = ap.parse_args()

    try:
        conn = mysql_conn(args)
    except Exception as e:                                   # noqa: BLE001
        print("✗ 连不上 MySQL（%s:%s/%s）：%s" % (args.host, args.port, args.db, e))
        return 2

    try:
        docs = fetch_mysql(conn, args.doc)
    finally:
        conn.close()

    print("=== MySQL ===")
    if not docs:
        print("  没有匹配的文档（is_deleted = 0）")
        return 1
    total_chunks = 0
    for d in docs.values():
        total_chunks += len(d["chunks"])
        flag = "" if d["status"] == "READY" else "  ← status=%s" % d["status"]
        print("  #%d《%s》%s 子块=%d embed=%s/%s%s%s" % (
            d["id"], d["title"], d["status"], len(d["chunks"]),
            d["embed_model"] or "-", d["dim"] or "-", flag,
            ("\n      失败原因：%s" % d["error_msg"]) if d.get("error_msg") else ""))
    print("  合计：%d 篇 / %d 个子块（未删除）" % (len(docs), total_chunks))

    if args.mysql_only:
        print("\n--mysql-only：未连接 Milvus。")
        return 0

    print("\n=== Milvus ===")
    rows, err = milvus_row_count(args)
    if err:
        print("  ✗ 不可用：%s" % err)
        print("  提示：本机启动脚本 E:\\Milvus\\standalone.bat；19530 未监听即为容器未起。")
        return 2
    print("  %s / %s 行数 = %d" % (args.milvus_db, args.collection, rows))

    bad = 0
    print("\n=== 逐篇比对 ===")
    for d in docs.values():
        doc_id = d["id"]
        mids, err = milvus_chunk_ids(args, doc_id)
        if err:
            print("  #%d《%s》✗ 查询失败：%s" % (doc_id, d["title"], err))
            bad += 1
            continue
        mysql_ids = sorted(int(c["id"]) for c in d["chunks"])
        if mysql_ids == mids:
            extra = ""
            if args.sample > 0 and mysql_ids:
                extra = sample_check(args, doc_id, d, mysql_ids)
            print("  #%d《%s》✓ MySQL %d = Milvus %d%s" % (doc_id, d["title"], len(mysql_ids), len(mids), extra))
        else:
            bad += 1
            miss = sorted(set(mysql_ids) - set(mids))
            ghost = sorted(set(mids) - set(mysql_ids))
            print("  #%d《%s》✗ MySQL %d / Milvus %d；缺 %d 条%s；多余 %d 条%s" % (
                doc_id, d["title"], len(mysql_ids), len(mids), len(miss),
                (" 如 %s" % miss[:5]) if miss else "", len(ghost),
                (" 如 %s" % ghost[:5]) if ghost else ""))

    print("\n=== 结论 ===")
    ok = (bad == 0 and rows == total_chunks)
    if ok:
        print("  ✓ 一致：Milvus 行数 = MySQL 子块数 = %d，逐篇 id 集合完全相同。" % rows)
        return 0
    if rows != total_chunks:
        print("  ✗ 总行数不一致：Milvus %d ≠ MySQL %d" % (rows, total_chunks))
    if bad:
        print("  ✗ %d 篇的 id 集合不一致" % bad)
    print("  处理：在管理端「知识库」对该文档点「重建」（重建只依赖 MySQL，会重写该文档的向量）。")
    return 1


def sample_check(args, doc_id, doc, mysql_ids):
    """抽样比对正文是否同源（超长块退化为前缀检查）"""
    texts, err = milvus_texts(args, doc_id)
    if err:
        return "（正文抽样跳过：%s）" % err
    by_id = {int(c["id"]): c for c in doc["chunks"]}
    checked = 0
    mismatch = 0
    for cid in mysql_ids:
        if checked >= args.sample:
            break
        if cid not in texts:
            continue
        checked += 1
        src = by_id[cid]["content"]
        got = texts[cid] or ""
        if len(src.encode("utf-8")) > MILVUS_TEXT_MAX_BYTES:
            if not got.endswith("...") and not src.startswith(got[:200]):
                mismatch += 1
        elif src != got:
            mismatch += 1
    if checked == 0:
        return ""
    return "；正文抽样 %d 条%s" % (checked, "" if mismatch == 0 else " 中 %d 条不一致 ✗" % mismatch)


if __name__ == "__main__":
    sys.exit(main())
