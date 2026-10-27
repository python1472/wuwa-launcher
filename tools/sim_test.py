#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用模拟器跑一遍 App 的关键流程，看会不会留下脏状态。

模拟器只能验证状态变化，不能验证悬浮窗与真实渲染。
"""

import sys
sys.path.insert(0, "tools")
from sim_device import Device, ShellSim, AppModel

PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [通过] %s" % name)
    else:
        FAIL += 1
        print("  [失败] %s  %s" % (name, detail))


def fresh():
    dev = Device()
    shell = ShellSim(dev)
    app = AppModel(shell)
    return dev, shell, app


def snapshot_of(dev):
    return dev.state()


# ============ 1. 档位计算 ============
print("\n[1] 档位计算（屏幕 1280×2800，密度 560）")
panel = {"w": 1280, "h": 2800, "d": 560}
expect = {
    1.0: (1280, 2800, 560),
    1.4: (1792, 3920, 784),
    1.6: (2048, 4480, 896),
    1.8: (2304, 5040, 1008),
    2.0: (2560, 5600, 1120),
    2.5: (3200, 7000, 1400),
    3.0: (3840, 8400, 1680),
}
for scale, (ew, eh, ed) in expect.items():
    m = AppModel.build_mode(panel["w"], panel["h"], panel["d"], scale)
    check("%.1f× → %dx%d @%ddpi" % (scale, ew, eh, ed),
          (m["w"], m["h"], m["d"]) == (ew, eh, ed),
          "实际 %dx%d @%d" % (m["w"], m["h"], m["d"]))

print("\n  风险分级：")
for scale in (1.4, 1.6, 1.8, 2.0, 2.5, 3.0):
    m = AppModel.build_mode(1280, 2800, 560, scale)
    names = ["稳妥", "有风险", "风险高", "极易崩溃"]
    print("    %.1f×  %dx%d  %.1fM  %s"
          % (scale, m["w"], m["h"], m["mp"], names[m["risk"]]))

# ============ 2. 完整流程：改分辨率 → 还原 ============
print("\n[2] 完整流程：1.4× 档启动 → 还原")
dev, shell, app = fresh()
before = snapshot_of(dev)
app.snapshot(dev.nav, dev.accel, dev.user_rot)
mode = AppModel.build_mode(1280, 2800, 560, 1.4)
app.apply_and_launch("tier", mode=mode, shizuku_ok=True)

check("分辨率已改为 1792×3920", (dev.phys_w, dev.phys_h) == (1792, 3920),
      "实际 %dx%d" % (dev.phys_w, dev.phys_h))
check("密度已改为 784", dev.phys_density == 784, "实际 %d" % dev.phys_density)
check("导航已切三键", dev.nav == 0, "实际 %d" % dev.nav)
check("自动旋转已打开", dev.accel == 1, "实际 %d" % dev.accel)

app.restore()
after = snapshot_of(dev)
check("还原后分辨率回原生", (dev.phys_w, dev.phys_h) == (1280, 2800))
check("还原后密度回 560", dev.phys_density == 560, "实际 %d" % dev.phys_density)
check("还原后导航回手势", dev.nav == 2 and dev.overlay == "gestural",
      "实际 nav=%d overlay=%s" % (dev.nav, dev.overlay))
check("还原后状态完全一致", before == after,
      "\n    前: %s\n    后: %s" % (before, after))
check("标记已清除", not app.applied)

# ============ 3. 原生档：什么都不该动 ============
print("\n[3] 原生档：不该改任何东西")
dev, shell, app = fresh()
before = snapshot_of(dev)
app.snapshot(dev.nav, dev.accel, dev.user_rot)
app.apply_and_launch("native", shizuku_ok=True)
check("原生档未改分辨率", (dev.phys_w, dev.phys_h) == (1280, 2800))
check("原生档未改导航", dev.nav == 2)
check("原生档未发出 wm 命令",
      not any(c.startswith("wm size ") and c != "wm size" for c in shell.log),
      "实际命令: %s" % shell.log)

# ============ 4. Shizuku 中途失效，还原必须保留标记 ============
print("\n[4] 还原时 Shizuku 失效 → 标记必须保留")
dev, shell, app = fresh()
app.snapshot(dev.nav, dev.accel, dev.user_rot)
mode = AppModel.build_mode(1280, 2800, 560, 1.6)
app.apply_and_launch("tier", mode=mode, shizuku_ok=True)
shell.available = False          # Shizuku 挂了
ok = app.restore()
check("还原失败被识别", not ok, "返回 %s" % ok)
check("标记被保留（下次可重试）", app.applied, "applied=%s" % app.applied)
shell.available = True
ok2 = app.restore()
check("Shizuku 恢复后能补还原成功", ok2 and not app.applied)
check("最终分辨率回原生", (dev.phys_w, dev.phys_h) == (1280, 2800))

# ============ 5. 无 Shizuku 启动：只动旋转 ============
print("\n[5] 无 Shizuku：跳过分辨率与导航，只动旋转")
dev, shell, app = fresh()
before = snapshot_of(dev)
app.snapshot(dev.nav, dev.accel, dev.user_rot)
app.apply_and_launch("tier", mode=AppModel.build_mode(1280, 2800, 560, 2.0),
                     shizuku_ok=False)
check("分辨率未变", (dev.phys_w, dev.phys_h) == (1280, 2800))
check("导航未变", dev.nav == 2)
app.restore()
check("还原后状态一致", before == snapshot_of(dev))

# ============ 6. 游戏运行中又去测试档位 ============
print("\n[6] 游戏运行中测试档位 → 结束不能清掉原有标记")
dev, shell, app = fresh()
app.snapshot(dev.nav, dev.accel, dev.user_rot)
game_mode = AppModel.build_mode(1280, 2800, 560, 1.6)
app.apply_and_launch("tier", mode=game_mode, shizuku_ok=True)
check("游戏启动后标记存在", app.applied)

was_applied = app.applied           # 复刻 TierTester.wasApplied
test_mode = AppModel.build_mode(1280, 2800, 560, 1.4)
app.apply_mode(test_mode["w"], test_mode["h"], test_mode["d"])
app.reset_mode()                    # 测试结束自动还原屏幕
if not was_applied:                 # 源码里的判断
    app.applied = False

check("测试结束后标记仍在", app.applied, "applied=%s" % app.applied)
app.restore()
check("最终能完整还原", (dev.phys_w, dev.phys_h) == (1280, 2800)
      and dev.nav == 2)

# ============ 7. 只改了旋转时的还原 ============
print("\n[7] 只开过自动旋转（未改分辨率）→ 还原应只回旋转")
dev, shell, app = fresh()
dev.accel = 0                        # 用户原本关着自动旋转
app.snapshot(dev.nav, 0, 0)
app.apply_and_launch("native", shizuku_ok=True)
check("自动旋转已打开", dev.accel == 1)
check("导航未动", dev.nav == 2)
app.nav_changed = False
app.rot_changed = True
app.applied = True
app.restore()
check("还原后自动旋转回关", dev.accel == 0, "实际 %d" % dev.accel)

print("\n" + "=" * 46)
print("通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 46)
sys.exit(1 if FAIL else 0)
