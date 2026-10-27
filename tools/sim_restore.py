#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟新的还原判断逻辑：以系统真实状态为准。

核心规则（与 Restorer.inspect 一一对应）：
  分辨率 → 看 wm size 输出里有没有 Override 行
  导航   → 跟快照比对
  旋转   → 跟快照比对
  没有快照 → 导航与旋转不乱动，只认 Override
"""

import sys

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


class Dev:
    """假手机：能表达「有没有 Override」"""

    def __init__(self):
        self.phys = (1280, 2800)
        self.phys_density = 560
        self.override = None          # None 表示原生
        self.override_density = None
        self.nav = 2
        self.accel = 1

    def wm_size(self):
        out = "Physical size: %dx%d" % self.phys
        if self.override:
            out += "\nOverride size: %dx%d" % self.override
        return out

    def wm_density(self):
        out = "Physical density: %d" % self.phys_density
        if self.override_density:
            out += "\nOverride density: %d" % self.override_density
        return out

    def has_override(self):
        return self.override is not None

    def has_density_override(self):
        return self.override_density is not None

    def set_size(self, w, h, d):
        self.override = (w, h)
        self.override_density = d

    def reset(self):
        self.override = None
        self.override_density = None


class Snap:
    def __init__(self):
        self.data = None
        self.clean = False

    def save(self, nav, accel, clean):
        self.data = {"nav": nav, "accel": accel}
        self.clean = clean

    def has(self):
        return self.data is not None


def inspect(dev, snap):
    """复刻 Restorer.inspect"""
    size_changed = dev.has_override() or dev.has_density_override()
    nav_changed = False
    rot_changed = False
    if snap.has():
        nav_changed = dev.nav != snap.data["nav"]
        rot_changed = dev.accel != snap.data["accel"]
    return size_changed, nav_changed, rot_changed


def needs(s):
    return any(s)


# ============ 1. 干净状态：什么都不用还原 ============
print("\n[1] 刚装好 App，屏幕是原生")
dev = Dev()
snap = Snap()
snap.save(dev.nav, dev.accel, clean=True)
st = inspect(dev, snap)
check("没有 Override", not st[0])
check("导航未变", not st[1])
check("整体不需要还原", not needs(st))
print("     → 界面按钮应为灰色，不发通知")

# ============ 2. 改了分辨率：需要还原 ============
print("\n[2] 启动 1.4× 后")
dev = Dev()
snap = Snap()
snap.save(2, 1, clean=True)
dev.set_size(1792, 3920, 784)
dev.nav = 0                     # 切了三键
dev.accel = 1                   # 开了自动旋转（本来也是 1，不算改）
st = inspect(dev, snap)
check("检测到 Override", st[0])
check("检测到导航变化", st[1])
check("旋转未变（原本就是开）", not st[2])
check("需要还原", needs(st))
print("     → 按钮应为可点，通知应出现")

# ============ 3. 还原后 ============
print("\n[3] 点还原之后")
dev.reset()
dev.nav = 2
st = inspect(dev, snap)
check("Override 消失", not st[0])
check("导航已回原值", not st[1])
check("不再需要还原", not needs(st))
print("     → 按钮应变回灰色，通知应消失")

# ============ 4. App 数据被清空，但屏幕还停在高分辨率 ============
print("\n[4] App 数据被清空，屏幕仍停在 1792×3920")
dev = Dev()
dev.set_size(1792, 3920, 784)
dev.nav = 0
snap = Snap()                   # 没有快照
st = inspect(dev, snap)
check("仍能检测到 Override（关键）", st[0])
check("无快照时不动导航", not st[1])
check("需要还原", needs(st))
print("     → 只还原分辨率，不碰导航，避免猜错")

# ============ 5. 重建服务时不该挂无用通知 ============
print("\n[5] 系统重建服务时已无东西可还原")
dev = Dev()
snap = Snap()
snap.save(2, 1, clean=True)
st = inspect(dev, snap)
check("判定为无需还原", not needs(st))
print("     → 应立刻取消通知并停掉服务")

# ============ 6. 划掉 App 不还原 ============
print("\n[6] 从最近任务划掉启动器")
dev = Dev()
dev.set_size(1792, 3920, 784)
st = inspect(dev, snap)
check("划掉后 Override 仍在（不自动还原）", st[0])
print("     → 这是设计如此：用户已通过触屏测试，屏幕可用，通知会留着")

# ============ 7. 再点一次启动，快照不该被覆盖 ============
print("\n[7] 游戏运行中又点一次启动")
dev = Dev()
snap = Snap()
snap.save(2, 1, clean=True)          # 原始：手势
dev.set_size(1792, 3920, 784)
dev.nav = 0                          # 已改三键

clean_now = not dev.has_override()   # False，不干净
if not snap.has() or clean_now:
    snap.save(dev.nav, dev.accel, clean_now)   # 源码里的条件

check("快照未被覆盖", snap.data["nav"] == 2,
      "当前记录 nav=%d，应为 2（手势）" % snap.data["nav"])

# ============ 结果 ============
print("\n" + "=" * 46)
print("通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 46)
sys.exit(1 if FAIL else 0)
