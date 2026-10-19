"""v10 评分标准脚本演练台。

为什么需要它：v10 是「改标准 + 加数据」型脚本，最危险的不是写错语法，
而是**静默失败**——第一版幂等守卫写反，18 条语句全部 OK 但一条数据都没进去。
所以演练必须覆盖三件事：
  1. 结构正确：题数/选项数/规则数符合预期
  2. 幂等：同一脚本连跑两次，第二次不重复插入
  3. 口径正确：GAD-7 重度 = D，PHQ-15/PSQI 无 D

做法：把真库里 4 张表的**表结构**复制到演练库，再把**当前数据**灌进去
（用 CREATE TABLE ... LIKE + INSERT ... SELECT，避免手抄 schema），
在演练库上跑脚本 —— 真库全程不被触碰。

用法：
    python rehearse_v10.py                # 重建演练库 + 跑两次（幂等验证）
    python rehearse_v10.py --keep         # 复用演练库（不清空），只跑一次
"""
import os
import re
import subprocess
import sys

import pymysql

SRC_DB = "psycare"
REHEARSE_DB = "psycare_rehearse_v10"
TABLES = ["scale", "scale_item", "scale_option", "scoring_rule"]
CONN = dict(host="127.0.0.1", port=3306, user="root", password="123456",
            charset="utf8mb4")

HERE = os.path.dirname(os.path.abspath(__file__))
SQL_FILE = os.path.abspath(os.path.join(
    HERE, "..", "..", "psycare-server", "sql",
    "v10_scale_standard_and_new_scales.sql"))
PY = sys.executable


def q(cur, sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()


def rebuild():
    """重建演练库：只复制 4 张表的**结构与数据**，其他表一概不管。"""
    conn = pymysql.connect(**CONN)
    cur = conn.cursor()
    cur.execute("DROP DATABASE IF EXISTS `%s`" % REHEARSE_DB)
    cur.execute("CREATE DATABASE `%s` DEFAULT CHARSET utf8mb4 "
                "COLLATE utf8mb4_general_ci" % REHEARSE_DB)
    for t in TABLES:
        cur.execute("CREATE TABLE `%s`.`%s` LIKE `%s`.`%s`"
                    % (REHEARSE_DB, t, SRC_DB, t))
        cur.execute("INSERT INTO `%s`.`%s` SELECT * FROM `%s`.`%s`"
                    % (REHEARSE_DB, t, SRC_DB, t))
    conn.commit()

    print("=== 演练库已重建：%s（复制自 %s）===" % (REHEARSE_DB, SRC_DB))
    for t in TABLES:
        n = q(cur, "SELECT COUNT(*) FROM `%s`.`%s`" % (REHEARSE_DB, t))[0][0]
        print("    %-14s %d 行" % (t, n))
    conn.close()


def snapshot():
    """抓一份结构快照，用于幂等对比。"""
    conn = pymysql.connect(database=REHEARSE_DB, **CONN)
    cur = conn.cursor()
    snap = {}
    for t in TABLES:
        snap[t] = q(cur, "SELECT COUNT(*) FROM `%s`" % t)[0][0]
    # 每张量表的题数/选项数明细
    snap["detail"] = q(cur, """
        SELECT s.code,
               COUNT(DISTINCT i.id) AS items,
               COUNT(o.id) AS opts,
               (SELECT COUNT(*) FROM scoring_rule r WHERE r.scale_id = s.id) AS rules
          FROM scale s
          LEFT JOIN scale_item i ON i.scale_id = s.id
          LEFT JOIN scale_option o ON o.item_id = i.id
         GROUP BY s.id, s.code ORDER BY s.id""")
    # 风险等级口径
    snap["levels"] = q(cur, """
        SELECT s.code, r.raw_level, r.risk_level
          FROM scoring_rule r JOIN scale s ON s.id = r.scale_id
         ORDER BY s.id, r.sort""")
    # 危机项
    snap["crisis"] = q(cur, """
        SELECT s.code, i.item_no FROM scale_item i
          JOIN scale s ON s.id = i.scale_id WHERE i.crisis_flag = 1""")
    conn.close()
    return snap


def run_script():
    """用项目自带的 run_sql_file.py 在演练库上执行 v10。"""
    r = subprocess.run(
        [PY, os.path.join(HERE, "run_sql_file.py"), SQL_FILE,
         "--db", REHEARSE_DB],
        capture_output=True, text=True, encoding="utf-8")
    out = (r.stdout or "") + (r.stderr or "")
    fails = [l for l in out.splitlines() if "] FAIL" in l]
    if r.returncode != 0 or fails:
        print(out)
        raise SystemExit("脚本执行失败！")
    # 只回显自检查询的结果行（以 "      -> " 开头）
    shown = [l for l in out.splitlines() if l.strip().startswith("->")]
    print("    脚本执行完成，%d 条语句全部 OK；自检输出："
          % len([l for l in out.splitlines() if "] OK" in l]))
    for l in shown:
        print("    " + l.strip().replace("-> ", "", 1))
    return out


def main():
    keep = "--keep" in sys.argv
    if not keep:
        rebuild()

    print("\n=== 第 1 次执行 v10 ===")
    run_script()
    s1 = snapshot()
    print("\n--- 结构快照 #1 ---")
    for code, items, opts, rules in s1["detail"]:
        print("    %-6s 题数=%-3d 选项数=%-4d 规则数=%d" % (code, items, opts, rules))

    print("\n=== 第 2 次执行 v10（幂等验证）===")
    run_script()
    s2 = snapshot()

    print("\n--- 幂等比对 ---")
    ok = True
    for t in TABLES:
        same = s1[t] == s2[t]
        ok &= same
        print("    %-14s %d -> %d  %s"
              % (t, s1[t], s2[t], "OK" if same else "FAIL 重复插入！"))
    same = s1["detail"] == s2["detail"]
    ok &= same
    print("    题数/选项数明细        %s" % ("OK" if same else "FAIL"))

    print("\n--- 风险等级口径（重点看 GAD7 重度 与 PHQ15/PSQI 是否无 D）---")
    for code, raw, lvl in s2["levels"]:
        print("    %-6s %-14s -> %s" % (code, raw, lvl))
    bad = [c for c, raw, lvl in s2["levels"]
           if c in ("PHQ15", "PSQI") and lvl == "D"]
    if bad:
        ok = False
        print("    FAIL 新量表不该有 D：%s" % bad)
    gad = [lvl for c, raw, lvl in s2["levels"] if c == "GAD7" and raw == "重度"]
    if gad and gad[0] == "D":
        print("    OK   GAD-7 重度 = D（与 PHQ-9 对齐）")
    else:
        ok = False
        print("    FAIL GAD-7 重度 = %s，应为 D" % gad)
    phq9 = [lvl for c, raw, lvl in s2["levels"] if c == "PHQ9" and raw == "重度"]
    print("    OK   PHQ-9 重度 = %s" % (phq9[0] if phq9 else "N/A"))

    print("\n--- 危机项（应只有 PHQ9 第 9 题）---")
    for code, no in s2["crisis"]:
        print("    %s 第 %d 题" % (code, no))
    # 注意：pymysql 的 fetchall() 返回**元组**，不能直接和 list 字面量比。
    if [tuple(r) for r in s2["crisis"]] != [("PHQ9", 9)]:
        ok = False
        print("    FAIL 危机项集合不符合预期（新量表不应有 crisis 题）")

    print("\n" + ("=== 演练全部通过 ===" if ok else "=== 演练存在问题，见上方 FAIL ==="))
    sys.exit(0 if ok else 2)


if __name__ == "__main__":
    main()
