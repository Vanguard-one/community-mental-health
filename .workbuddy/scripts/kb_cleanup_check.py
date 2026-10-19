# -*- coding: utf-8 -*-
"""知识库清理与导入后的状态核对（**只读**）。

为什么单独写一个：删语料是「数据操作」，核对是「只读探针」。
硬约定要求服务一律用户自己启动、我只做只读探测，所以这个脚本**不删任何东西**，
它只回答三个问题：
    1. 库表现在是什么样（含逻辑删除的行，因为这些行仍占 id 位）；
    2. Milvus 里还有哪些向量的 chunk_id（rowCount 会虚高，不能当判据，见硬约定 #91）；
    3. MySQL 与 Milvus 两侧的 chunk_id 集合是否一致（验收 Phase 3 的核心断言）。

用法：
    python kb_cleanup_check.py                  # 打印当前状态
    python kb_cleanup_check.py --sql            # 额外打印清理用的 SQL（供人工执行）
"""

import argparse
import json
import os
import re
import sys
import urllib.request

import pymysql

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DB = dict(host="127.0.0.1", port=3306, user="root", password="123456",
          database="psycare", charset="utf8mb4")
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def read_milvus():
    p = os.path.join(BASE_DIR, "psycare-server", "src", "main", "resources", "application.yml")
    cfg = open(p, encoding="utf-8").read()
    m = re.search(r"milvus:\s*\n((?:\s+.*\n)*?)(?=\n\s{2}\w|\Z)", cfg)
    blk = m.group(1) if m else ""

    def grab(k, d=""):
        mm = re.search(rf"{k}:\s*(\S+)", blk)
        return mm.group(1) if mm else d

    return dict(enabled=grab("enabled", "true"),
                uri=grab("uri", "http://localhost:19530"),
                token=grab("token", ""),
                collection=grab("collection", "psycare_chunks"))


def milvus_query(mv, expr="chunk_id >= 0", limit=200):
    """用 REST /v2/entities/query 取实际 chunk_id 集合。

    为什么不用 rowCount：Bounded 一致性下统计值含已删除标记（实测虚高一倍），
    只有取回实际主键集合才能与 MySQL 对齐（硬约定 #91）。
    """
    url = mv["uri"].rstrip("/") + "/v2/vectordb/entities/query"
    body = json.dumps({
        "collectionName": mv["collection"],
        "filter": expr,
        "outputFields": ["chunk_id", "doc_id"],
        "limit": limit,
    }).encode()
    hdr = {"Content-Type": "application/json"}
    if mv["token"]:
        hdr["Authorization"] = "Bearer " + mv["token"]
    req = urllib.request.Request(url, data=body, headers=hdr)
    try:
        with OPENER.open(req, timeout=15) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        return {"error": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sql", action="store_true", help="额外打印清理 SQL")
    args = ap.parse_args()

    c = pymysql.connect(**DB)
    cur = c.cursor()

    print("=" * 70)
    print("1. MySQL 侧")
    print("=" * 70)
    cur.execute("SELECT id,title,source_type,status,chunk_count,is_deleted "
                "FROM kb_document ORDER BY id")
    docs = cur.fetchall()
    if not docs:
        print("  kb_document 表为空")
    for r in docs:
        flag = "（已逻辑删除）" if r[5] else ""
        print(f"  id={r[0]:<4} {r[1]:<24} type={r[2]:<12} "
              f"status={r[3]:<8} chunks={r[4]} {flag}")

    cur.execute("SELECT doc_id,chunk_type,COUNT(*) FROM kb_chunk "
                "GROUP BY doc_id,chunk_type ORDER BY doc_id")
    chunks = cur.fetchall()
    print()
    if not chunks:
        print("  kb_chunk 表为空（无任何分块）")
    for r in chunks:
        print(f"  doc_id={r[0]:<4} {r[1]:<8} {r[2]} 块")

    # 未删除文档应有多少子块
    cur.execute("SELECT doc_id, COUNT(*) FROM kb_chunk "
                "WHERE is_deleted=0 AND chunk_type='CHILD' GROUP BY doc_id")
    my_child = {r[0]: r[1] for r in cur.fetchall()}
    c.close()

    print()
    print("=" * 70)
    print("2. Milvus 侧（用实际 chunk_id 集合，不用 rowCount）")
    print("=" * 70)
    mv = read_milvus()
    if mv["enabled"] != "true":
        print("  psycare.milvus.enabled=false，跳过")
    else:
        res = milvus_query(mv)
        if "error" in res:
            print(f"  查询失败：{res['error']}")
            print("  （Milvus 容器可能未启动，属正常情况）")
            milvus_ids = set()
        else:
            rows = res.get("data") or []
            milvus_ids = {r.get("chunk_id") for r in rows}
            print(f"  collection={mv['collection']}  实际返回 {len(rows)} 行")
            if milvus_ids:
                print(f"  chunk_id 集合：{sorted(x for x in milvus_ids if x is not None)}")
            else:
                print("  （无任何向量）")

        print()
        print("=" * 70)
        print("3. 两侧一致性（Phase 3 的核心断言）")
        print("=" * 70)
        # MySQL 的子块 id 才是「应该存在于 Milvus 的向量」的真值
        c = pymysql.connect(**DB)
        cur = c.cursor()
        cur.execute("SELECT id FROM kb_chunk WHERE chunk_type='CHILD' AND is_deleted=0")
        mysql_ids = {r[0] for r in cur.fetchall()}
        c.close()

        only_mysql = mysql_ids - milvus_ids
        only_milvus = milvus_ids - mysql_ids
        if not mysql_ids and not milvus_ids:
            print("  ✓ 两侧都为空（库已清空，可以直接导入新语料）")
        elif not only_mysql and not only_milvus:
            print(f"  ✓ 一致：{len(mysql_ids)} 个子块 id 两侧完全相同")
        else:
            if only_mysql:
                print(f"  ✗ 仅 MySQL 有（Milvus 缺向量，需点「重建」）：{sorted(only_mysql)}")
            if only_milvus:
                print(f"  ✗ 仅 Milvus 有（孤儿向量，删除文档未清干净）：{sorted(only_milvus)}")
            print("  ⚠ 处理：在管理端点对应文档的「重建」，或删掉文档重新导入")

    if args.sql:
        print()
        print("=" * 70)
        print("清理 SQL（供人工执行；本脚本不执行任何写操作）")
        print("=" * 70)
        print("""
-- 目标：清掉所有已逻辑删除的知识库文档（含《爬虫知识》），以及它们的残留分块。
-- 分块用物理删（与 KnowledgeIndexService.delete 的实现一致）。

-- 1) 先看清要删什么（务必先跑这条）
SELECT id, title, status, chunk_count, is_deleted, deleted_at
  FROM kb_document WHERE is_deleted = 1;

SELECT doc_id, COUNT(*) FROM kb_chunk
 WHERE doc_id IN (SELECT id FROM kb_document WHERE is_deleted = 1)
 GROUP BY doc_id;

-- 2) 物理删分块（先删子表）
DELETE FROM kb_chunk
 WHERE doc_id IN (SELECT id FROM kb_document WHERE is_deleted = 1);

-- 3) 物理删文档
DELETE FROM kb_document WHERE is_deleted = 1;

-- 4) 核对（两条都应为 0）
SELECT COUNT(*) FROM kb_document WHERE is_deleted = 1;
SELECT COUNT(*) FROM kb_chunk;

-- 5) Milvus 侧：被删文档的向量应由 delete 接口同步清掉；
--    若发现孤儿向量（kb_cleanup_check.py 第 3 节报「仅 Milvus 有」），
--    在管理端对相关文档点「重建」即可，无需手工操作 Milvus。
""")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
