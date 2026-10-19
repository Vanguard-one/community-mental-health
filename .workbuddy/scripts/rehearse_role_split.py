"""
在 scratch 库中演练 v2/v3 脚本，验证 DDL 与迁移 SQL 可真实执行且数据一致。
只操作自建库 psycare_mig_test，不触碰真实库 psycare。

校验设计：引用完整性用「迁移前 vs 迁移后」对比，而不是断言「必须为 0」。
原因：真实库里本来就存在脏数据，硬断言为 0 会把「既有问题」误判成「迁移引入」。
"""
import re
import sys
import pymysql

SRC = "psycare"
DST = "psycare_mig_test"
SQL_DIR = r"D:\WorkBuddy\2026-09-23-14-52-33\community-mental-health\psycare-server\sql"

CONN = dict(host="127.0.0.1", port=3306, user="root", password="123456",
            charset="utf8mb4", autocommit=True)

TABLES = ["sys_user", "resident_profile", "doctor_profile", "scale", "scale_item",
          "scale_option", "scoring_rule", "assessment", "assessment_answer",
          "alert_record", "alert_status_log", "consult_session", "consult_message",
          "recommendation_log", "notification"]

# (表, 引用列, 合法 id 来源的角色) —— 用于跨表引用完整性 before/after 对比
REFS = [
    ("assessment",         "resident_id", ("RESIDENT",)),
    ("alert_record",       "resident_id", ("RESIDENT",)),
    ("alert_record",       "handler_id",  ("DOCTOR", "ADMIN")),
    ("consult_session",    "resident_id", ("RESIDENT",)),
    ("consult_session",    "doctor_id",   ("DOCTOR",)),
    ("recommendation_log", "resident_id", ("RESIDENT",)),
    ("recommendation_log", "doctor_id",   ("DOCTOR",)),
    ("notification",       "user_id",     ("RESIDENT", "DOCTOR", "ADMIN")),
]


def split_statements(sql_text):
    """剥掉行注释后按分号切分。脚本内无字符串含分号，安全。"""
    lines = [ln for ln in sql_text.splitlines() if not ln.strip().startswith("--")]
    body = re.sub(r"--[^\n]*", "", "\n".join(lines))
    return [s.strip() for s in body.split(";") if s.strip()]


def run(cur, sql_text, label):
    stmts = split_statements(sql_text)
    print(f"\n{'='*72}\n{label}  共 {len(stmts)} 条语句\n{'='*72}")
    for i, s in enumerate(stmts, 1):
        head = " ".join(s.split())[:76]
        try:
            cur.execute(s)
            if s.lstrip().upper().startswith("SELECT"):
                rows = cur.fetchall()
                for r in rows[:8]:
                    print(f"  [{i}] {head}\n      -> {r}")
                if not rows:
                    print(f"  [{i}] {head}\n      -> (0 行)")
            else:
                print(f"  [{i}] OK  {head}")
        except Exception as e:
            print(f"  [{i}] FAIL  {head}\n      !! {e}")
            raise


def orphan_map(cur, resolver):
    """返回 {(表.列): 悬空引用条数}。resolver(roles) -> 合法 id 的 SELECT 语句。"""
    out = {}
    for table, col, roles in REFS:
        src = resolver(roles)
        cur.execute(f"SELECT COUNT(*) FROM {DST}.{table} x"
                    f" LEFT JOIN ({src}) r ON r.id = x.{col}"
                    f" WHERE x.{col} IS NOT NULL AND r.id IS NULL")
        out[f"{table}.{col}"] = cur.fetchone()[0]
    return out


def main():
    conn = pymysql.connect(**CONN)
    cur = conn.cursor()

    print(f"--- 重建 scratch 库 {DST} ---")
    cur.execute(f"DROP DATABASE IF EXISTS {DST}")
    cur.execute(f"CREATE DATABASE {DST} DEFAULT CHARACTER SET utf8mb4")

    print(f"--- 从 {SRC} 克隆 {len(TABLES)} 张表结构与数据 ---")
    for t in TABLES:
        cur.execute(f"CREATE TABLE {DST}.{t} LIKE {SRC}.{t}")
        cur.execute(f"INSERT INTO {DST}.{t} SELECT * FROM {SRC}.{t}")
        cur.execute(f"SELECT COUNT(*) FROM {DST}.{t}")
        print(f"  {t:22s} {cur.fetchone()[0]:>4d} 行")

    # ---- 迁移前基线（按 sys_user.role 判定合法 id）----
    before = orphan_map(cur, lambda roles:
                        f"SELECT id FROM {DST}.sys_user WHERE role IN "
                        f"({','.join(repr(r) for r in roles)})")
    print(f"\n{'-'*72}\n迁移前基线：悬空引用（既有脏数据）\n{'-'*72}")
    for k, v in before.items():
        print(f"  {k:34s} {v}")
    dirty_total = sum(before.values())
    print(f"  {'合计':34s} {dirty_total}"
          + ("   ← 既有脏数据，非迁移引入" if dirty_total else ""))

    ok = True
    for fname, label in [("v2_role_split_ddl.sql", "V2 DDL"),
                         ("v3_role_split_migration.sql", "V3 迁移")]:
        with open(f"{SQL_DIR}\\{fname}", encoding="utf-8") as f:
            sql_text = f.read().replace("USE psycare;", f"USE {DST};")
        run(cur, sql_text, f"{label}（{fname}）")

    # ---- 迁移后（按角色表判定合法 id）----
    after = orphan_map(cur, lambda roles:
                       "SELECT id FROM " + " UNION ALL SELECT id FROM ".join(
                           f"{DST}.`{ {'RESIDENT': 'user', 'DOCTOR': 'doctor', 'ADMIN': 'admin'}[r] }`"
                           for r in roles))
    print(f"\n{'='*72}\n引用完整性：迁移前 vs 迁移后（必须完全相等）\n{'='*72}")
    for k in before:
        same = before[k] == after[k]
        if not same:
            ok = False
        flag = "OK" if same else "!!! 迁移引入新断链"
        print(f"  [{flag}] {k:34s} 前 {before[k]:>3d} → 后 {after[k]:>3d}")

    # ---- 条数与结构断言 ----
    print(f"\n{'='*72}\n条数与结构断言\n{'='*72}")
    num_checks = [
        ("三表总数 - sys_user 总数（应为 0）",
         f"SELECT (SELECT COUNT(*) FROM {DST}.`user`) + (SELECT COUNT(*) FROM {DST}.`doctor`)"
         f" + (SELECT COUNT(*) FROM {DST}.`admin`) - (SELECT COUNT(*) FROM {DST}.sys_user)"),
        ("医生档案未搬全数（应为 0）",
         f"SELECT COUNT(*) FROM {DST}.doctor_profile_bak_20260928 b"
         f" LEFT JOIN {DST}.doctor d ON d.id = b.user_id WHERE d.id IS NULL"),
        ("居民档案未搬全数（应为 0）",
         f"SELECT COUNT(*) FROM {DST}.resident_profile_bak_20260928 b"
         f" LEFT JOIN {DST}.`user` u ON u.id = b.user_id WHERE u.id IS NULL"),
        ("存量医生 audit_status 非 APPROVED 数（应为 0）",
         f"SELECT COUNT(*) FROM {DST}.doctor WHERE audit_status <> 'APPROVED'"),
        ("已删除账号数（迁移后应为 0）",
         f"SELECT COUNT(*) FROM {DST}.sys_user WHERE is_deleted = 1"),
        ("角色表残留账号身份字段列数（应为 0）",
         f"SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '{DST}'"
         f" AND TABLE_NAME IN ('user','doctor','admin')"
         f" AND COLUMN_NAME IN ('username','password','role','status','is_deleted')"),
        ("SUPER 管理员数（应 >= 1）",
         f"SELECT COUNT(*) FROM {DST}.admin WHERE admin_level = 'SUPER'"),
    ]
    for name, q in num_checks:
        cur.execute(q)
        v = cur.fetchone()[0]
        good = (v == 0) if "应为 0" in name else (v >= 1)
        if not good:
            ok = False
        print(f"  [{'OK' if good else '!!! 不符合预期'}] {name}: {v}")

    print(f"\n{'-'*72}\n结构断言：角色表列清单（不应含账号身份字段）\n{'-'*72}")
    for t in ["user", "doctor", "admin"]:
        cur.execute(f"SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                    f" WHERE TABLE_SCHEMA='{DST}' AND TABLE_NAME='{t}' ORDER BY COLUMN_NAME")
        cols = [r[0] for r in cur.fetchall()]
        print(f"  {t:8s} ({len(cols):>2d} 列): {' '.join(cols)}")

    print(f"\n{'-'*72}\n软删 + 同名重注册验证（作用在 sys_user 基表）\n{'-'*72}")
    cur.execute(f"SELECT id, username, role FROM {DST}.sys_user ORDER BY id LIMIT 1")
    uid, uname, urole = cur.fetchone()
    cur.execute(f"UPDATE {DST}.sys_user SET is_deleted = 1, deleted_at = NOW() WHERE id = %s", (uid,))
    print(f"  已软删 id={uid} username={uname}（role={urole}）")
    try:
        cur.execute(f"INSERT INTO {DST}.sys_user (username, password, real_name, phone, role,"
                    f" status, is_deleted, created_at)"
                    f" VALUES (%s, 'x', '新注册', '', 'RESIDENT', 1, 0, NOW())", (uname,))
        print("  [OK] 同名重新注册成功 —— 函数索引按预期放行已删除行")
    except Exception as e:
        ok = False
        print(f"  [!!! 不符合预期] 同名重注册被拦：{e}")

    cur.execute(f"SELECT COUNT(*) FROM {DST}.sys_user")
    total = cur.fetchone()[0]
    cur.execute(f"SELECT COUNT(*) FROM {DST}.sys_user WHERE is_deleted = 0")
    alive = cur.fetchone()[0]
    print(f"  物理行数 = {total}，MP 可见行数 = {alive}，被软删屏蔽 = {total - alive}")
    if total - alive < 1:
        ok = False
        print("  [!!! 不符合预期] 软删未屏蔽任何行")

    print(f"\n{'='*72}\n演练结论：{'全部通过' if ok else '存在不符合预期的项，见上方 !!!'}\n{'='*72}")
    print(f"scratch 库 {DST} 保留供人工查看；确认后可 DROP DATABASE {DST};")
    cur.close()
    conn.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
