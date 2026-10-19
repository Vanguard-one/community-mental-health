"""表级备份：导出建表语句 + 全部数据到 .workbuddy/backup/。

为什么不用 mysqldump：本机 PATH 上没有 mysqldump（MySQL 装的是 Server，
客户端工具没进 PATH），而备份是「删/改数据前必须做」的一步，不能因为
工具缺失就跳过。

用法：
    python dump_table.py consult_message
    python dump_table.py consult_message assessment --tag before-v4

输出：.workbuddy/backup/<表名>-<tag>-<时间戳>.sql
"""
import datetime
import os
import sys

import pymysql

BACKUP_DIR = r"D:\WorkBuddy\2026-09-23-14-52-33\community-mental-health\.workbuddy\backup"
DB = dict(host="127.0.0.1", port=3306, user="root", password="123456",
          database="psycare", charset="utf8mb4")


def sql_literal(v):
    """转成 SQL 字面量。只处理本库用到的类型（int/str/datetime/None）。"""
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v).replace("\\", "\\\\").replace("'", "\\'")
    # 换行会破坏单条 INSERT 的可读性，转成转义序列
    s = s.replace("\r", "\\r").replace("\n", "\\n")
    return "'" + s + "'"


def dump(cur, table, tag, ts):
    out = ["-- 备份 %s（%s）  %s" % (table, tag, ts), "USE psycare;"]
    cur.execute("SHOW CREATE TABLE %s" % table)
    create = cur.fetchone()[1]
    out.append("DROP TABLE IF EXISTS %s;" % table)
    out.append(create + ";")

    cur.execute("SELECT * FROM %s" % table)
    rows = cur.fetchall()
    cols = [d[0] for d in cur.description]
    for r in rows:
        out.append("INSERT INTO %s (%s) VALUES (%s);"
                   % (table, ",".join(cols), ",".join(sql_literal(v) for v in r)))

    fn = os.path.join(BACKUP_DIR, "%s-%s-%s.sql" % (table, tag, ts))
    with open(fn, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    return fn, len(rows), os.path.getsize(fn)


def main():
    args = sys.argv[1:]
    tag = "backup"
    if "--tag" in args:
        i = args.index("--tag")
        tag = args[i + 1]
        del args[i:i + 2]
    if not args:
        print(__doc__)
        sys.exit(1)

    os.makedirs(BACKUP_DIR, exist_ok=True)
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    for t in args:
        fn, n, size = dump(cur, t, tag, ts)
        print("%-24s 行数=%-5d 字节=%-8d %s" % (t, n, size, fn))
    conn.close()


if __name__ == "__main__":
    main()
