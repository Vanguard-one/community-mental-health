"""Redis 探针：零依赖（直接走 RESP 协议），验证连通性与读写回环。

为什么要单独验这一步：AI 引导问诊的**阶段状态**（Phase 2 的 `ai:stage:*`）计划
存在 Redis 里而非 MySQL —— 它是一次会话内的短生命周期状态，不该为它建表。
但这也意味着「Redis 不可用」会直接表现为「阶段永远停在第一步」，
且这种降级是静默的（写失败不报错、读不到就重建）。所以先把它验掉。

顺带说明本机坑：Redis 缺了会表现为「登录成功后立刻被弹回登录页」——
登录态本身就存在 Redis 里，不是 JWT 无状态。

用法：
    python redis_probe.py            # PING + SET/GET/DEL 回环
    python redis_probe.py --keys     # 额外只读统计各前缀键数量（用 SCAN，不用 KEYS）
"""
import socket
import sys

HOST, PORT = "127.0.0.1", 6379


def encode(*args):
    """编码成 RESP 数组（客户端到服务端的统一格式）。"""
    out = ["*%d\r\n" % len(args)]
    for a in args:
        b = a.encode() if isinstance(a, str) else a
        out.append("$%d\r\n" % len(b))
        out.append(b.decode("latin-1"))
        out.append("\r\n")
    return "".join(out).encode("latin-1")


def read_reply(f):
    """读一条 RESP 回复（够用即可：+ - : $ * 四种）。"""
    line = f.readline()
    if not line:
        return None
    kind, body = line[:1], line[1:].rstrip(b"\r\n")
    if kind == b"+":
        return body.decode("utf-8", "replace")
    if kind == b"-":
        return Exception(body.decode("utf-8", "replace"))
    if kind == b":":
        return int(body)
    if kind == b"$":
        n = int(body)
        if n == -1:
            return None
        data = f.read(n + 2)
        return data[:-2].decode("utf-8", "replace")
    if kind == b"*":
        n = int(body)
        if n == -1:
            return None
        return [read_reply(f) for _ in range(n)]
    return line


def cmd(f, *args):
    f.write(encode(*args))
    f.flush()
    return read_reply(f)


def scan_count(f, pattern):
    """用 SCAN 游标迭代统计，绝不使用 KEYS（KEYS 在大 key 空间会阻塞整个实例）。"""
    cursor, total = "0", 0
    while True:
        res = cmd(f, "SCAN", cursor, "MATCH", pattern, "COUNT", "200")
        cursor = res[0]
        total += len(res[1])
        if cursor == "0":
            return total


def main():
    sock = socket.create_connection((HOST, PORT), timeout=3)
    f = sock.makefile("rwb")

    pong = cmd(f, "PING")
    print("PING           -> %s" % pong)

    # 读写回环：用与 Phase 2 一致的键前缀，验证前缀本身也通
    probe_key = "ai:stage:__probe__"
    set_ret = cmd(f, "SET", probe_key, "ok")
    print("SET probe      -> %s" % set_ret)
    val = cmd(f, "GET", probe_key)
    print("GET probe      -> %s" % val)
    cmd(f, "DEL", probe_key)
    print("DEL probe      -> 读回 %s" % cmd(f, "GET", probe_key))

    ok = (pong == "PONG" and set_ret == "OK" and val == "ok")

    if "--keys" in sys.argv:
        print("DBSIZE         -> %s 个键" % cmd(f, "DBSIZE"))
        # 只统计数量，不输出键名（键名里可能含 token）
        for p in ["login:", "token:", "ai:stage:", "auth:", "session:"]:
            print("  %-14s %s" % (p + "*", scan_count(f, p + "*")))

    sock.close()
    print("结果：%s" % ("Redis 可读写 OK" if ok else "Redis 异常 FAIL"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
