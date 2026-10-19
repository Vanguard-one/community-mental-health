#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""检查表单页字段完整性：表单对象里的字段是否都在模板里有输入控件。

起因（2026-09-28 的真实事故）：`Register.vue` 重写时漏掉了 `password` / `confirmPassword`
两个 `a-form-item` —— 它们在 `formState` 和 `rules` 里都存在，`onFinish` 也照常提交，
**只有模板里的输入框没了**。于是用户打开注册页发现「没有填密码的地方」。
`vue-tsc` 与 `vite build` 全都不报错，因为规则对象和模板字段之间本来就没有强类型关联。

分级报告：
  ① 高置信度 —— `rules` 里配了校验规则，但模板里没有对应字段（本次事故就属这类，
     配了 required 却没输入框，纯浪费且说明字段漏了）
  ② 待确认   —— 表单对象里有字段但模板未绑定（可能是纯内部状态，人工判断）

用法：python check_form_fields.py <前端 src 目录>
"""
import re
import sys
from pathlib import Path

FORM_DECL = re.compile(
    r"const\s+(\w+)\s*=\s*(?:reactive|ref)\s*(?:<[^>]*>)?\s*\(\s*\{(.*?)\n\}",
    re.S,
)


def scan(path: Path) -> tuple[list[str], list[str]]:
    text = path.read_text(encoding="utf-8")
    sm = re.search(r"<script setup[^>]*>(.*?)</script>", text, re.S)
    if not sm:
        return [], []
    script = sm.group(1)
    # 模板区：到 <style> 之前为止（避免 script/style 里的 name= 干扰）
    tpl = text[: text.index("<style")] if "<style" in text else text

    # 模板中真正绑定了输入控件的字段名
    bound = set(re.findall(r'\bname="(\w+)"', tpl))
    for m in re.finditer(r'v-model(?::\w+)?="([\w.]+)"', tpl):
        bound.add(m.group(1).split(".")[-1])

    hard, soft = [], []
    for obj, body in FORM_DECL.findall(script):
        # 只取顶层 key（缩进 2 空格），忽略嵌套对象
        keys = [m.group(1) for m in re.finditer(r"^\s{2}(\w+)\s*:", body, re.M)]
        if not keys:
            continue
        # 该对象对应字段在模板中的其它合法用法：tab 按钮的 @click 赋值、条件渲染等，
        # 只要模板里出现过 `obj.key` 就算已接线（v-model / name= 已在 bound 里）
        for k in re.findall(rf"\b{re.escape(obj)}\.(\w+)\b", tpl):
            bound.add(k)
        # 该对象对应的 rules：优先同名 rules 变量，其次全局 rules
        rules = re.search(r"const\s+\w*[Rr]ules\w*\s*=\s*(?:computed<[^>]*>\s*\(\s*)?\(?\s*\{", script)
        rule_body = script[rules.start():] if rules else ""
        rule_keys = set(re.findall(r"^\s*(\w+)\s*:\s*\[", rule_body, re.M))

        for k in keys:
            if k in bound:
                continue
            if k in rule_keys:
                hard.append(f"{path.name}  [{obj}.{k}] rules 里有校验规则，但模板没有该字段的输入控件")
            else:
                soft.append(f"{path.name}  [{obj}.{k}] 表单对象里有，模板未绑定（若为内部状态可忽略）")
    return hard, soft


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    hard, soft = [], []
    files = 0
    for f in sorted(list(root.rglob("*.vue"))):
        if "node_modules" in f.parts or "dist" in f.parts or ".dist-" in str(f):
            continue
        files += 1
        h, s = scan(f)
        hard += h
        soft += s

    print(f"扫描 {files} 个 .vue 文件")
    if hard:
        print(f"\n[高置信度] {len(hard)} 处 —— 建议修：")
        for i in hard:
            print("  " + i)
    if soft:
        print(f"\n[待确认] {len(soft)} 处：")
        for i in soft:
            print("  " + i)
    if not hard and not soft:
        print("表单字段与模板绑定一致，未发现问题")
    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main())
