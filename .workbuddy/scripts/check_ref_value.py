#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
扫描 <script setup> / store setup 里「computed / ref 漏写 .value」的错误。

背景：模板中 ref 会自动解包，script 中不会。`const isDoctor = computed(...)` 后
在 script 里写 `if (isDoctor)` 会**恒为真**（对象 truthy），且 vue-tsc 不报错、
构建也通过 —— 只能靠运行时发现，或靠这个脚本。

用法：python check_ref_value.py <前端 src 目录>
"""
import re
import sys
from pathlib import Path

DECL = re.compile(r"const\s+(\w+)\s*=\s*(?:computed|ref|shallowRef|toRef)\s*[(<]")


def script_body(text: str) -> str | None:
    """取出 <script setup> 内容；非 SFC（纯 .ts）返回 None 表示整文件可用。"""
    m = re.search(r"<script[^>]*setup[^>]*>(.*?)</script>", text, re.S)
    return m.group(1) if m else None


def scan(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    body = script_body(text)
    if body is None:
        if path.suffix != ".ts":
            return []
        body = text

    # 去掉注释与字符串，避免误判
    clean = re.sub(r"//[^\n]*", "", body)
    clean = re.sub(r"/\*.*?\*/", "", clean, flags=re.S)
    clean = re.sub(r"`[^`]*`|'[^']*'|\"[^\"]*\"", "``", clean)

    issues = []
    for name in set(DECL.findall(clean)):
        for m in re.finditer(r"(?<![\w.$])" + re.escape(name) + r"\b(?!\s*\.\s*value)", clean):
            tail = clean[m.end():m.end() + 40].strip()
            # 类型标注 / 声明本身 / 作为参数整体传递（watch(isDoctor, …)）是合法的
            if tail.startswith(("=", ":", ")", ",", "]", "<", "as ", "?", ";", "|")):
                continue
            line_no = clean[:m.start()].count("\n") + 1
            issues.append(f"{path.name}:{line_no}  {m.group(0)}  ->  ...{clean[max(0, m.start()-40):m.end()+40].strip()}")
            break  # 每个变量只报第一处，够定位了
    return issues


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    files = sorted(list(root.rglob("*.vue")) + list(root.rglob("*.ts")))
    all_issues = []
    for f in files:
        if "node_modules" in f.parts or "dist" in f.parts or ".dist-" in str(f):
            continue
        all_issues.extend(scan(f))

    if all_issues:
        print(f"发现 {len(all_issues)} 处疑似缺 .value：")
        for i in all_issues:
            print("  " + i)
        return 1
    print(f"扫描 {len(files)} 个文件，未发现 script 内漏写 .value")
    return 0


if __name__ == "__main__":
    sys.exit(main())
