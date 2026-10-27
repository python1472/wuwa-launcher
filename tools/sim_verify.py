#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
交叉核对：模拟器用的命令，是否和 Java 源码里真正发出去的一致。

避免「模拟器测的是我复刻的逻辑，而复刻本身写错了」这种情况。
"""

import re
import os
import sys

SRC = "src/com/miaojian/wuwalauncher"
SIM = "tools/sim_device.py"
TEST = "tools/sim_test.py"


def read(path):
    return open(path, encoding="utf-8").read()


def java_commands():
    """从 Java 源码里抽出所有 shell 命令模板"""
    cmds = set()
    for f in os.listdir(SRC):
        if not f.endswith(".java"):
            continue
        text = read(os.path.join(SRC, f))
        # Shell.ok("...") / Shell.exec("...") / Shell.run("...")
        for m in re.finditer(r'Shell\.(?:ok|exec|run)\(\s*"([^"]+)"', text):
            cmds.add(m.group(1).strip())
        # 拼接形式："wm size " + xxx
        for m in re.finditer(r'"(wm size |wm density |settings put [a-z]+ [a-z_]+ |'
                             r'cmd overlay enable-exclusive --category |pidof |'
                             r'am force-stop )"\s*\+', text):
            cmds.add(m.group(1).strip() + " <arg>")
    return cmds


def sim_commands():
    """模拟器里实现的命令前缀"""
    text = read(SIM) + read(TEST)
    cmds = set()
    for m in re.finditer(r'"(wm size[^"]*|wm density[^"]*|'
                         r'settings put [a-z]+ [a-z_]+[^"]*|'
                         r'cmd overlay enable-exclusive --category[^"]*|'
                         r'pidof[^"]*)"', text):
        cmds.add(m.group(1).strip())
    return cmds


print("=" * 60)
print("Java 源码里出现的 shell 命令：")
for c in sorted(java_commands()):
    print("   ", c)

print()
print("模拟器里实现的命令：")
for c in sorted(sim_commands()):
    print("   ", c)

# 核心命令必须两边都有
required = [
    "wm size",
    "wm size reset",
    "wm density",
    "wm density reset",
    "settings put secure navigation_mode",
    "cmd overlay enable-exclusive --category",
]

# 旋转不走 shell，而是 App 用 WRITE_SETTINGS 直接写 Settings.System。
# 模拟器里用等价的 settings 命令表达，所以单独核对。
rotation_via_api = [
    ("Settings.System.putInt", "Java 侧用 API 写入"),
    ("ACCELEROMETER_ROTATION", "自动旋转键"),
    ("USER_ROTATION", "方向键"),
]

print()
print("=" * 60)
print("shell 命令覆盖检查：")
java_text = " ".join(read(os.path.join(SRC, f))
                     for f in os.listdir(SRC) if f.endswith(".java"))
sim_text = read(SIM) + read(TEST)

miss = 0
for kw in required:
    in_java = kw in java_text
    in_sim = kw in sim_text
    mark = "OK " if (in_java and in_sim) else "!! "
    if not (in_java and in_sim):
        miss += 1
    print("  %s%-46s java=%s sim=%s"
          % (mark, kw, "有" if in_java else "无", "有" if in_sim else "无"))

print()
print("旋转写入路径检查（这条不需要 shell）：")
for kw, desc in rotation_via_api:
    in_java = kw in java_text
    mark = "OK " if in_java else "!! "
    if not in_java:
        miss += 1
    print("  %s%-30s %s" % (mark, kw, desc))

print()
if miss:
    print("有 %d 项没对齐，模拟结果不可信" % miss)
    sys.exit(1)
else:
    print("全部对齐：模拟器覆盖了源码里实际会用到的命令与写入路径")
