"""
把 v2 DDL 与 v3 迁移应用到真实库 psycare，并跑校验。
脚本内先做一次全表快照（v2 脚本自带备份表），执行后逐项断言。
用法：python apply_role_split.py [--dry-run]
"""
import re
import sys
import pymysql

DB = "psycare"
SQL_DIR = r"D:\WorkBuddy\2026-09-23-14-52-33\community-mental-health\psycare-server\sql"

CONN = dict(host="127.0.0.1", port=3306, user="root", password="123456",
            charset="utf8mb4", autocommit=True)


def split_statements(sql_text):
    lines = [ln for ln in sql_text.splitlines() if not ln.strip().startswith("--")]
    body = re.sub(r"--[^\n]*", "", "\n".join(lines))
    return [s.strip() for s in body.split(";") if s.strip()]


def main():
    dry = "--dry-run" in sys.argv
    conn = pymysql.connect(**CONN)
    cur = conn.cursor()

    # 前置检查：确认是否已经迁移过，避免重复执行报 1060
    cur.execute("SELECT COUNT(*) FROM information_schema.TABLES "
                "WHERE TABLE_SCHEMA=%s AND TABLE_NAME IN ('user','doctor','admin')", (DB,))
    existing = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM information_schema.COLUMNS "
                "WHERE TABLE_SCHEMA=%s AND TABLE_NAME='sys_user' AND COLUMN_NAME='is_deleted'", (DB,))
    has_col = cur.fetchone()[0]
    print(f"--- 前置检查 ---\n  角色表已存在数: {existing}/3    sys_user.is_deleted 已存在: {bool(has_col)}")
    if existing > 0 or has_col:
        print("  !! 检测到已迁移过。为避免 1060/1061 报错，本次不执行。")
        print("     如需重来：先执行 v3 文件第 3 节的回滚方案。")
        return 1
    if dry:
        print("  dry-run：仅检查，不执行。")
        return 0

    for fname, label in [("v2_role_split_ddl.sql", "V2 DDL"),
                         ("v3_role_split_migration.sql", "V3 迁移")]:
        with open(f"{SQL_DIR}\\{fname}", encoding="utf-8") as f:
            sql_text = f.read()
        stmts = split_statements(sql_text)
        print(f"\n{'='*70}\n{label}（{fname}） {len(stmts)} 条语句\n{'='*70}")
        for i, s in enumerate(stmts, 1):
            head = " ".join(s.split())[:72]
            try:
                cur.execute(s)
                if s.lstrip().upper().startswith("SELECT"):
                    rows = cur.fetchall()
                    for r in rows[:8]:
                        print(f"  [{i}] -> {r}")
                    if not rows:
                        print(f"  [{i}] {head}\n      -> (0 行)")
                else:
                    print(f"  [{i}] OK  {head}")
            except Exception as e:
                print(f"  [{i}] FAIL  {head}\n      !! {e}")
                return 1

    print(f"\n{'='*70}\n迁移后校验\n{'='*70}")
    cur.execute(f"SELECT role, COUNT(*) FROM {DB}.sys_user GROUP BY role")
    src = dict(cur.fetchall())
    cur.execute(f"SELECT (SELECT COUNT(*) FROM {DB}.`user`), "
                f"(SELECT COUNT(*) FROM {DB}.doctor), (SELECT COUNT(*) FROM {DB}.admin)")
    dst = cur.fetchone()
    checks = [
        ("居民条数 源=目标", src.get("RESIDENT", 0) == dst[0], f"{src.get('RESIDENT',0)} = {dst[0]}"),
        ("医生条数 源=目标", src.get("DOCTOR", 0) == dst[1], f"{src.get('DOCTOR',0)} = {dst[1]}"),
        ("管理员条数 源=目标", src.get("ADMIN", 0) == dst[2], f"{src.get('ADMIN',0)} = {dst[2]}"),
    ]
    cur.execute(f"SELECT COUNT(*) FROM {DB}.doctor WHERE audit_status <> 'APPROVED'")
    checks.append(("存量医生全为 APPROVED", cur.fetchone()[0] == 0, "非 APPROVED 数 = 0"))
    cur.execute(f"SELECT COUNT(*) FROM {DB}.admin WHERE admin_level='SUPER'")
    super_cnt = cur.fetchone()[0]
    checks.append(("至少一个 SUPER 管理员", super_cnt >= 1, f"SUPER 数 = {super_cnt}"))
    cur.execute(f"SELECT COUNT(*) FROM {DB}.sys_user WHERE is_deleted=1")
    checks.append(("迁移后无已删账号", cur.fetchone()[0] == 0, "is_deleted=1 数 = 0"))
    cur.execute(f"SELECT COUNT(*) FROM {DB}.assessment a LEFT JOIN {DB}.`user` u "
                f"ON u.id=a.resident_id WHERE u.id IS NULL")
    checks.append(("测评引用不断链", cur.fetchone()[0] == 0, "断链数 = 0"))
    cur.execute(f"SELECT COUNT(*) FROM {DB}.alert_record a LEFT JOIN {DB}.`user` u "
                f"ON u.id=a.resident_id WHERE u.id IS NULL")
    checks.append(("工单引用不断链", cur.fetchone()[0] == 0, "断链数 = 0"))

    ok = True
    for name, good, detail in checks:
        if not good:
            ok = False
        print(f"  [{'OK' if good else '!!! 不符合预期'}] {name}: {detail}")

    cur.execute(f"SELECT id, role, username, status, is_deleted FROM {DB}.sys_user ORDER BY id")
    print("\n--- sys_user 现状 ---")
    for r in cur.fetchall():
        print("  ", r)
    for t in ["user", "doctor", "admin"]:
        cur.execute(f"SELECT * FROM {DB}.`{t}` ORDER BY id")
        print(f"--- {t} ---")
        for r in cur.fetchall():
            print("  ", r)

    cur.close()
    conn.close()
    print(f"\n{'='*70}\n应用{'成功' if ok else '完成但有不符项'}\n{'='*70}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
