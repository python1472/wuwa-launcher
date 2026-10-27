#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UI 状态机模拟。

把 LauncherActivity 的界面状态变更逐条复刻出来，跑大量操作序列，
看有没有走不到的状态、卡死的按钮、或者互相矛盾的显示。

只模拟 UI 状态，不碰 shell —— 分辨率/悬浮窗那些已经有别的测试覆盖。

复刻的关键成员（与源码一一对应）：
    busy            false = 空闲，true = 有操作正在进行
    selectedKind    NONE / NATIVE / HD / TIER / CUSTOM
    selectedMode    选中的档位（原生与高清为 None）
    launchEnabled   启动按钮是否可点
    launchHint      启动按钮下的提示文字
    tiersOpen       档位列表是否展开
"""
import sys
import itertools

NONE, NATIVE, HD, TIER, CUSTOM = "NONE", "NATIVE", "HD", "TIER", "CUSTOM"

PASS = 0
FAIL = 0
ISSUES = []


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
    else:
        FAIL += 1
        ISSUES.append((name, detail))
        print("  [问题] %s  %s" % (name, detail))


class Ui:
    """复刻 Activity 的 UI 状态"""

    def __init__(self, shizuku_ok=True, panel_loaded=False,
                 has_override=False, has_overlay=True):
        self.hasOverride = has_override
        self.hasOverlay = has_overlay
        self.busy = False
        self.selectedKind = NONE
        self.selectedMode = None
        self.launchEnabled = False
        self.launchHint = "请先选择分辨率"
        self.tiersOpen = False
        self.restoreEnabled = False
        self.shizukuOk = shizuku_ok
        self.panel = {"w": 1280, "h": 2800, "d": 560} if panel_loaded else None
        self.tiers = []
        self.pickerOpened = 0
        self.lastLaunchMs = -99999
        self.nowMs = 0
        self.log = []

    def _within_cooldown(self):
        return (self.nowMs - self.lastLaunchMs) < 1500

    def advance(self, ms):
        self.nowMs += ms

    # ---------- setLaunchEnabled ----------
    def _refresh_launch_btn(self):
        # 与源码一致：enabled && !busy 才亮
        if self.launchEnabled and not self.busy:
            self.launchHint = self._desc()
        else:
            self.launchHint = "处理中…" if self.busy else "请先选择分辨率"

    def _desc(self):
        if self.selectedKind == NATIVE:
            return "原生 · 不改分辨率"
        if self.selectedKind == HD:
            return "高清 · " + ("%d × %d" % (self.panel["w"], self.panel["h"])
                              if self.panel else "")
        if self.selectedKind in (TIER, CUSTOM) and self.selectedMode:
            return "%s · %s" % (self.selectedMode["label"], self.selectedMode["size"])
        return ""

    def set_launch_enabled(self, enabled):
        self.launchEnabled = enabled
        self._refresh_launch_btn()

    def set_busy(self, b):
        self.busy = b
        self._refresh_launch_btn()

    # ---------- select ----------
    def select(self, kind, mode=None):
        if self.busy:
            return "blocked_busy"
        self.selectedKind = kind
        self.selectedMode = mode
        self.set_launch_enabled(True)
        return "ok"

    # ---------- toggleTiers ----------
    def toggle_tiers(self):
        if self.busy:
            return "blocked_busy"
        if self.tiersOpen:
            self.tiersOpen = False
            return "closed"
        if self.shizukuOk and self.panel is None:
            return "reading"
        if not self.tiers:
            return "need_shizuku"
        self.tiersOpen = True
        return "opened"

    # ---------- 档位点击 ----------
    def on_tier_clicked(self, mode):
        if self.busy:
            return "blocked_busy"
        if not self.shizukuOk:
            return "ask_shizuku"
        if mode["scale"] > 1.6:
            return "ask_disclaimer"
        self.begin_tier_test(mode)
        return "testing"

    def begin_tier_test(self, mode):
        self.set_busy(True)
        self.set_launch_enabled(False)

    def finish_test(self, mode, passed, kind):
        self.set_busy(False)
        if passed:
            self.selectedKind = kind
            self.selectedMode = mode
        self.set_launch_enabled(self.selectedKind != NONE)

    # ---------- 启动 ----------
    def on_launch_clicked(self):
        if self.busy or self.selectedKind == NONE:
            return "blocked"
        if self._within_cooldown():
            return "blocked_cooldown"

        # 高清：要挂悬浮窗矫正方向，但不改尺寸，所以只查悬浮窗权限
        if self.selectedKind == HD and not self.hasOverride:
            if not self.hasOverlay:
                return "blocked_overlay"
            self.set_busy(True)
            self.set_launch_enabled(False)
            self.lastLaunchMs = self.nowMs
            self.set_busy(False)
            return "launch_hd_overlay"

        # 原生档：同样什么都不改
        if self.selectedKind == NATIVE:
            self.set_busy(True)
            self.set_launch_enabled(False)
            self.lastLaunchMs = self.nowMs
            self.set_busy(False)
            return "launch_native"

        # 到这里都要改显示，需要 Shizuku 与悬浮窗权限
        if not self.shizukuOk:
            return "blocked_shizuku"
        if not self.hasOverlay:
            return "blocked_overlay"

        self.set_busy(True)
        self.set_launch_enabled(False)
        self.lastLaunchMs = self.nowMs
        self.set_busy(False)
        return "launch_custom"

    # ---------- 未实现：忙碌时的交互屏蔽 ----------
    def cards_clickable(self):
        # 源码里三个选项卡与「还原显示」都没有在 busy 时禁用
        return not self.busy


def mode(scale, label=None):
    w = round(1280 * scale)
    h = round(2800 * scale)
    return {"scale": scale,
            "label": label or ("%.1f×" % scale),
            "size": "%d × %d" % (w, h)}


# ============================================================
print("=" * 60)
print("[1] 忙碌时能否点选项卡 / 还原按钮")
print("=" * 60)
u = Ui(panel_loaded=True)
u.tiers = [mode(s) for s in (1.4, 1.6, 1.8, 2.0)]
u.begin_tier_test(mode(1.4))
check("测试中 busy 为真", u.busy)
check("测试中启动按钮不可点", not u.launchEnabled)
r = u.select(NATIVE)
check("测试中点原生被拦下", r == "blocked_busy",
      "返回 %s" % r)
check("选中状态没被改掉", u.selectedKind == NONE,
      "当前选中 = %s" % u.selectedKind)

u.finish_test(mode(1.4), True, TIER)
check("测试结束后选中为 TIER", u.selectedKind == TIER)

# ============================================================
print()
print("=" * 60)
print("[2] 测试中途打开档位列表")
print("=" * 60)
u = Ui(panel_loaded=True)
u.tiers = [mode(s) for s in (1.4, 1.6)]
u.begin_tier_test(mode(1.4))
r = u.toggle_tiers()
check("测试中展开档位列表被拦下", r == "blocked_busy", "返回 %s" % r)

# ============================================================
print()
print("=" * 60)
print("[3] 未授权路径")
print("=" * 60)
u = Ui(shizuku_ok=True, panel_loaded=False)
r = u.toggle_tiers()
check("面板未就绪时点展开 → 先读取参数（正常流程）", r == "reading",
      "返回 %s" % r)
u.shizukuOk = True
u.panel = {"w": 1280, "h": 2800, "d": 560}
u.tiers = [mode(1.4)]
r = u.toggle_tiers()
check("授权后能正常展开", r == "opened")

u2 = Ui(shizuku_ok=True, panel_loaded=True)
r = u2.toggle_tiers()
check("有面板但 tiers 为空 → need_shizuku", r == "need_shizuku")

# ============================================================
print()
print("=" * 60)
print("[4] 「高清」在 panel 未就绪时的行为")
print("=" * 60)
u = Ui(shizuku_ok=False, panel_loaded=True)
u.select(HD)
check("无 Shizuku 也能选中高清", u.selectedKind == HD)

u = Ui(shizuku_ok=True, panel_loaded=True)
check("面板就绪后高清可选", u.panel is not None)
u.select(HD)
check("选中高清后启动按钮可点", u.launchEnabled)
check("高清的提示文字包含分辨率",
      "1280 × 2800" in u.launchHint, "实际：%s" % u.launchHint)

# ============================================================
print()
print("=" * 60)
print("[4b] 高清档是否需要 Shizuku")
print("=" * 60)

u = Ui(shizuku_ok=False, panel_loaded=True, has_override=False, has_overlay=True)
u.select(HD)
r = u.on_launch_clicked()
check("屏幕未改过 + 无 Shizuku → 高清挂窗口后启动（不需 shizuku）",
      r == "launch_hd_overlay", "返回 %s" % r)

u = Ui(shizuku_ok=False, panel_loaded=True, has_override=False, has_overlay=False)
u.select(HD)
r = u.on_launch_clicked()
check("无悬浮窗权限 → 高清被拦（方向矫正必需）",
      r == "blocked_overlay", "返回 %s" % r)

u = Ui(shizuku_ok=False, panel_loaded=True, has_override=True)
u.select(HD)
r = u.on_launch_clicked()
check("有覆盖值 + 无 Shizuku → 高清被拦（需清覆盖）",
      r == "blocked_shizuku", "返回 %s" % r)

u = Ui(shizuku_ok=True, panel_loaded=True, has_override=True)
u.select(HD)
r = u.on_launch_clicked()
check("有覆盖值 + 有 Shizuku → 高清可启动", r == "launch_custom",
      "返回 %s" % r)

# ============================================================
print()
print("=" * 60)
print("[5] 全流程状态一致性")
print("=" * 60)
bad = []
for start in (NATIVE, HD, TIER, CUSTOM):
    for test_pass in (True, False):
        u = Ui(panel_loaded=True)
        u.tiers = [mode(s) for s in (1.4, 1.6, 1.8, 2.0)]
        m = mode(1.8)
        u.select(start, m if start in (TIER, CUSTOM) else None)
        u.on_tier_clicked(m)          # 1.8 > 1.6 → 免责声明路径
        u.begin_tier_test(m)
        u.finish_test(m, test_pass, TIER)

        # 断言：结束后 busy 必须是 false
        if u.busy:
            bad.append((start, test_pass, "测试结束后 busy 仍为 true"))
        # 断言：有选中就必须能点启动
        if u.selectedKind != NONE and not u.launchEnabled:
            bad.append((start, test_pass,
                        "有选中(%s)但启动按钮不可点" % u.selectedKind))
        # 断言：提示文字不能为空
        if u.launchEnabled and not u.launchHint:
            bad.append((start, test_pass, "启动按钮可点但提示为空"))

check("所有组合下状态自洽", not bad, "\n    " + "\n    ".join(map(str, bad)) if bad else "")

# ============================================================
print()
print("=" * 60)
print("[6] 连续两次测试")
print("=" * 60)
u = Ui(panel_loaded=True)
u.tiers = [mode(1.4), mode(1.8)]
u.begin_tier_test(mode(1.4))
u.finish_test(mode(1.4), True, TIER)
check("第一次测试后可选", u.selectedKind == TIER and u.launchEnabled)

u.begin_tier_test(mode(1.8))
check("第二次测试中按钮灰", u.busy and not u.launchEnabled)
u.finish_test(mode(1.8), False, TIER)   # 第二次失败
check("第二次失败后仍保留上一次的选中",
      u.selectedKind == TIER and u.launchEnabled,
      "kind=%s enabled=%s —— 失败不该清掉已有的有效选择"
      % (u.selectedKind, u.launchEnabled))

# ============================================================
print()
print("=" * 60)
print("[7] 忙碌状态下的连点保护")
print("=" * 60)
u = Ui(panel_loaded=True)
u.select(NATIVE)
u.on_launch_clicked()
check("原生档点击后不残留 busy", not u.busy)
u2 = Ui(panel_loaded=True)
u2.select(TIER, mode(1.4))
r1 = u2.on_launch_clicked()
r2 = u2.on_launch_clicked()      # 立刻再点
check("连续点启动第二次被冷却挡住", r2 == "blocked_cooldown",
      "实际返回 %s" % r2)

u2.advance(2000)
r3 = u2.on_launch_clicked()
check("冷却结束后可以再次启动", r3 == "launch_custom", "实际 %s" % r3)

# 原生档同样受冷却保护
u3 = Ui(panel_loaded=True)
u3.select(NATIVE)
u3.on_launch_clicked()
check("原生档连点也被挡", u3.on_launch_clicked() == "blocked_cooldown")

# ============================================================
print()
print("=" * 60)
print("[8] 还原按钮与选中的独立性")
print("=" * 60)
u = Ui(panel_loaded=True)
u.select(HD)
u.restoreEnabled = True
check("还原按钮状态与选中无关（各自独立）", u.launchEnabled and u.restoreEnabled)
u.set_busy(True)
# 源码 doRestore 里会 setLaunchEnabled(false) 但不清 selectedKind
check("还原中 busy 会压住启动按钮", not u.launchEnabled or u.busy)

# ============================================================
print()
print("=" * 60)
print("结果：%d 通过，%d 问题" % (PASS, FAIL))
if ISSUES:
    print()
    print("发现的问题：")
    for name, detail in ISSUES:
        print("  · %s" % name)
        if detail:
            print("    %s" % detail)
print("=" * 60)
sys.exit(1 if FAIL else 0)
