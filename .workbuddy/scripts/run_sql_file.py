"""执行 .sql 脚本文件到 psycare 库（按分号切分逐条执行）。

为什么不用 mysql 客户端：本机 PATH 上没有 mysql.exe。脚本内容按项目惯例
写成「单条普通 SQL + PREPARE/EXECUTE」形式（无存储过程、无分号字符串），
所以按分号切分是安全的 —— 切分器会跳过 `--` 注释行与空行。

用法：
    python run_sql_file.py psycare-server/sql/v4_ai_message_finish_reason.sql
    python run_sql_file.py xxx.sql --dry-run     # 只打印将执行的语句
    python run_sql_file.py xxx.sql --db psycare_ddl_check
        # 在别的库上演练（把脚本里的 `USE psycare;` 改写成目标库）。
        # 项目惯例：DDL 先演练再上真库；建表类脚本用一次性空库跑一遍最省事。
"""
import os
import re
import sys

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password="123456",
          charset="utf8mb4")


def retarget(text, db):
    """把脚本里的 USE psycare; 改写成 USE <db>;（演练用）"""
    if not db:
        return text
    return re.sub(r"^(\s*USE\s+)`?\w+`?\s*;", r"\1`%s`;" % db, text,
                  flags=re.IGNORECASE | re.MULTILINE)


def split_statements(text):
    """按分号切分，剥掉 `--` 整行注释。"""
    lines = []
    for raw in text.splitlines():
        s = raw.strip()
        if not s or s.startswith("--"):
            continue
        lines.append(raw)
    body = "\n".join(lines)
    return [s.strip() for s in body.split(";") if s.strip()]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    dry = "--dry-run" in sys.argv
    db = sys.argv[sys.argv.index("--db") + 1] if "--db" in sys.argv else None

    with open(path, encoding="utf-8") as f:
        stmts = split_statements(retarget(f.read(), db))

    if dry:
        for i, s in enumerate(stmts, 1):
            print("[%02d] %s" % (i, re.sub(r"\s+", " ", s)[:110]))
        print("共 %d 条" % len(stmts))
        return

    conn = pymysql.connect(**DB)
    if db:
        cur0 = conn.cursor()
        cur0.execute("CREATE DATABASE IF NOT EXISTS `%s` "
                     "DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci" % db)
        conn.commit()
        print("演练目标库已就绪: %s" % db)
    cur = conn.cursor()
    for i, s in enumerate(stmts, 1):
        head = re.sub(r"\s+", " ", s)[:70]
        try:
            cur.execute(s)
            rows = cur.fetchall() if cur.description else None
            print("[%02d] OK   %s" % (i, head))
            for r in (rows or []):
                print("      -> %s" % (r,))
        except Exception as e:
            print("[%02d] FAIL %s\n      %s" % (i, head, e))
            conn.close()
            sys.exit(1)
    conn.commit()
    conn.close()
    print("全部执行完成:", os.path.basename(path))


if __name__ == "__main__":
    main()
