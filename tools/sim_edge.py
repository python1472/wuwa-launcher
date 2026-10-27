#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
边界场景测试：重复操作、快照覆盖、异常中断。

这些是纯状态推演，模拟器的局限见 sim_device.py 顶部说明。
"""

import sys
sys.path.insert(0, "tools")
from sim_device import Device, ShellSim, AppModel

PASS = 0
FAIL = 0
PROBLEMS = []


def check(name, cond, detail="", severity="bug"):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [通过] %s" % name)
    else:
        FAIL += 1
        print("  [问题] %s  %s" % (name, detail))
        PROBLEMS.append((severity, name, detail))


def fresh():
    dev = Device()
    shell = ShellSim(dev)
    app = AppModel(shell)
    return dev, shell, app


# ============ 场景 A：连续点两次启动 ============
print("\n[A] 游戏运行中又点了一次启动")
dev, shell, app = fresh()
app.snapshot(dev.nav, dev.accel, dev.user_rot)      # 第一次：nav=2 手势
first_snap = dict(app.snap)
m = AppModel.build_mode(1280, 2800, 560, 1.6)
app.apply_and_launch("tier", mode=m, shizuku_ok=True)

# 第二次点启动 —— 源码里已有未还原改动时不再覆盖快照
re_snap = app.snapshot(dev.nav, dev.accel, dev.user_rot)

check("第二次没有覆盖快照", not re_snap and app.snap == first_snap,
      "第一次 nav=%d，现在 nav=%d" % (first_snap["nav"], app.snap["nav"]))

# 第二次选原生档：navChanged=false，但改动标记必须保留上一次的
app.apply_and_launch("native", shizuku_ok=True)
check("原生档没有擦掉「改过导航」的记录", app.nav_changed,
      "nav_changed=%s" % app.nav_changed)

app.restore()
check("还原后导航回到用户原本的手势模式",
      dev.nav == first_snap["nav"],
      "期望 nav=%d，实际 nav=%d" % (first_snap["nav"], dev.nav))

# ============ 场景 B：测试档位后选原生档启动 ============
print("\n[B] 测试完档位，再选原生档启动")
dev, shell, app = fresh()
app.snapshot(dev.nav, dev.accel, dev.user_rot)
# 测试 1.4x
m = AppModel.build_mode(1280, 2800, 560, 1.4)
app.applied = True
app.apply_mode(m["w"], m["h"], m["d"])
app.reset_mode()
app.applied = False
# 选原生档启动 —— 源码里原生档直接 launchGame()，不 snapshot 也不打标记
before_nav = dev.nav
check("原生档启动后标记仍为假", not app.applied)
check("原生档启动未改导航", dev.nav == before_nav)

# ============ 场景 C：改分辨率成功但切导航失败 ============
print("\n[C] 改了分辨率，切三键导航失败")
dev = Device()
shell = ShellSim(dev, fail_wm=False)
app = AppModel(shell)
app.snapshot(dev.nav, dev.accel, dev.user_rot)


class FailingNav(ShellSim):
    def exec(self, cmd):
        if cmd.startswith("cmd overlay") or cmd.startswith("settings put secure"):
            self.log.append(cmd)
            return False, "denied"
        return ShellSim.exec(self, cmd)


shell2 = FailingNav(dev)
app.shell = shell2
m = AppModel.build_mode(1280, 2800, 560, 1.8)
ok = app.apply_and_launch("tier", mode=m, shizuku_ok=True)
check("导航失败被识别为部分失败", not ok)
check("分辨率仍然改上去了", (dev.phys_w, dev.phys_h) == (2304, 5040),
      "实际 %dx%d" % (dev.phys_w, dev.phys_h))
check("导航未变（仍为手势）", dev.nav == 2, "实际 %d" % dev.nav)

# ============ 场景 D：还原时连 reset 都失败 ============
print("\n[D] 屏幕卡在高分辨率，reset 也失败")
dev, shell, app = fresh()
app.snapshot(dev.nav, dev.accel, dev.user_rot)
m = AppModel.build_mode(1280, 2800, 560, 2.0)
app.apply_and_launch("tier", mode=m, shizuku_ok=True)
shell.fail_wm = True
ok = app.restore()
check("还原失败被识别", not ok)
check("标记保留，等下次重试", app.applied)
check("屏幕仍停在 2560×5600（如实反映）",
      (dev.phys_w, dev.phys_h) == (2560, 5600))
shell.fail_wm = False
ok2 = app.restore()
check("重试后能还原", ok2 and (dev.phys_w, dev.phys_h) == (1280, 2800))

# ============ 场景 E：不同倍率来回切几次 ============
print("\n[E] 连续切换多个倍率后还原")
dev, shell, app = fresh()
app.snapshot(dev.nav, dev.accel, dev.user_rot)
for s in (1.4, 1.6, 1.8, 2.0, 1.4):
    m = AppModel.build_mode(1280, 2800, 560, s)
    app.apply_mode(m["w"], m["h"], m["d"])
    app.applied = True
app.restore()
check("多次切换后仍能干净还原",
      (dev.phys_w, dev.phys_h) == (1280, 2800) and dev.phys_density == 560)

# ============ 场景 F：2K 屏上的档位 ============
print("\n[F] 换一台 2K 屏手机（1440×3200，密度 640）")
for s in (1.4, 1.6, 2.0, 3.0):
    m = AppModel.build_mode(1440, 3200, 640, s)
    names = ["稳妥", "有风险", "风险高", "极易崩溃"]
    print("    %.1f×  %dx%d @%ddpi  %.1fM  %s"
          % (s, m["w"], m["h"], m["d"], m["mp"], names[m["risk"]]))
m14 = AppModel.build_mode(1440, 3200, 640, 1.4)
check("2K 屏 1.4× 已进入风险区（本机是稳妥）",
      m14["risk"] >= 1,
      "风险等级 %d —— 说明档位确实按屏幕现算，没有写死"
      % m14["risk"])

# ============ 结果 ============
print("\n" + "=" * 50)
print("通过 %d 项，问题 %d 项" % (PASS, FAIL))
if PROBLEMS:
    print("\n发现的问题：")
    for sev, name, detail in PROBLEMS:
        print("  · %s" % name)
        if detail:
            print("    %s" % detail)
print("=" * 50)
