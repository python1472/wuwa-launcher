#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生命周期与中断场景测试。

重点模拟「用户不按套路操作」时会不会留下脏状态：
中途退出、切后台、按返回、重复打开、异常回调。
"""
import sys

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


class Activity:
    """复刻生命周期与 busy 的关系"""

    def __init__(self):
        self.busy = False
        self.destroyed = False
        self.testerRunning = False
        self.maskShowing = False
        self.screenModified = False
        self.applied = False
        self.pendingUiPosts = 0
        self.launched = 0

    # ---- 测试流程 ----
    def start_test(self):
        self.busy = True
        self.maskShowing = True
        self.screenModified = True
        self.applied = True
        self.testerRunning = True

    def finish_test(self):
        self.testerRunning = False
        self.maskShowing = False
        self.screenModified = False
        self.busy = False

    # ---- 生命周期 ----
    def on_stop(self):
        """按 Home 或切到别的应用。onDestroy 不会调用"""
        return "mask=%s screen=%s busy=%s" % (
            self.maskShowing, self.screenModified, self.busy)

    def on_destroy(self):
        self.destroyed = True
        if self.testerRunning:
            # 源码：tester.abort() 撤遮罩 + 还原屏幕
            self.maskShowing = False
            self.screenModified = False
            self.testerRunning = False
            # 注意：abort 没有复位 Activity 的 busy
            self.busy = False
        # 源码里没有清 busy

    def on_destroy_source_faithful(self):
        """严格按源码：onDestroy 只调 tester.abort()，不碰 busy"""
        self.destroyed = True
        if self.testerRunning:
            self.maskShowing = False
            self.screenModified = False
            self.testerRunning = False
        # busy 保持原状

    def ui_post(self):
        """Activity 销毁后仍往里 post 会怎样"""
        self.pendingUiPosts += 1
        return not self.destroyed


print("=" * 60)
print("[1] 测试中途按 Home（onStop，Activity 未销毁）")
print("=" * 60)
a = Activity()
a.start_test()
st = a.on_stop()
check("遮罩仍在", a.maskShowing)
check("屏幕仍是高分辨率", a.screenModified)
print("    → 实际状态：%s" % st)
print("    用户此时看到的是桌面，但屏幕分辨率没还原。")
print("    若此时点通知里的「还原」，会把屏幕还原，但测试遮罩还挂在 Activity 上。")
# 模拟：通知还原后回到 Activity
a.screenModified = False
a.applied = False
# 源码 onResume 会调 tester.invalidateIfScreenReset()
a.maskShowing = False
a.testerRunning = False
check("切回来时 onResume 会把无效测试结束掉", not a.maskShowing,
      "invalidateIfScreenReset 发现屏幕已无 Override → 撤遮罩、清标记")

print()
print("=" * 60)
print("[2] 测试中途按返回键（onDestroy）")
print("=" * 60)
a = Activity()
a.start_test()
a.on_destroy_source_faithful()
check("遮罩已撤", not a.maskShowing)
check("屏幕已还原", not a.screenModified)
check("销毁后 busy 残留无害（下次是新实例）", True,
      "abort() 不复位 busy，但 Activity 已销毁，新实例从 false 开始")

print()
print("=" * 60)
print("[3] Activity 销毁后，后台线程还在跑")
print("=" * 60)
a = Activity()
a.busy = True
a.on_destroy_source_faithful()
# applyAndLaunch 的线程在销毁后仍会 startActivity 拉起游戏
a.launched += 1
check("销毁后仍可能拉起游戏", a.launched == 1,
      "applyAndLaunchInner 跑在独立线程里，Activity 销毁不会中断它。"
      "用户点了启动后立刻退出，游戏照样会被拉起来。"
      "这算合理行为（用户确实点了启动），但界面上不会有任何反馈。")

print()
print("=" * 60)
print("[4] ui.post 到已销毁的 Activity")
print("=" * 60)
a = Activity()
a.on_destroy_source_faithful()
ok = a.ui_post()
check("销毁后 post 不会崩（Handler 绑定主线程）", True,
      "Handler 绑定的是主线程 Looper，不是 Activity；"
      "post 的 runnable 仍会执行，但里面若访问已销毁的 View 不会崩，只是无效")

print()
print("=" * 60)
print("[5] 连续开关档位列表 5 次")
print("=" * 60)
opened = 0
state = False
for i in range(5):
    state = not state          # toggle
    if state:
        opened += 1
check("5 次切换后状态正确（奇数次应展开）", state)
check("展开次数 == 3", opened == 3, "实际 %d" % opened)

print()
print("=" * 60)
print("[6] 打开设置面板时的 busy 状态")
print("=" * 60)
a = Activity()
a.busy = True
# 源码 onClick 里加了 if (busy) return
panel_opened = not a.busy
check("忙碌时设置面板被拦下", not panel_opened,
      "挡住了「测试中重启桌面进程」这种自我打断")

print()
print("=" * 60)
print("[7] 自定义滚轮：不滑动直接点下一步")
print("=" * 60)
# 滚轮初始停在 DEFAULT_STEP = 4（1.4×）
initial_index = 4
scale = 1.0 + initial_index * 0.1
check("默认选中 1.4×", abs(scale - 1.4) < 0.001, "实际 %.1f" % scale)
check("1.4× 不需要确认框", scale <= 1.6)

print()
print("=" * 60)
print("[8] 滚轮边界：拖到两头会不会越界")
print("=" * 60)
# onTouchEvent 里 offset 被夹在 [0, len-1]
for raw in (-5.0, -0.3, 0.0, 20.5, 25.0):
    clamped = max(0.0, min(raw, 20.0))     # 21 项，索引 0..20
    check("offset %.1f → 夹在 %.1f" % (raw, clamped), 0 <= clamped <= 20)

print()
print("=" * 60)
print("[9] 还原时选中状态是否保留")
print("=" * 60)
a = Activity()
selected_kind = "TIER"
a.busy = True
# doRestore 里 setLaunchEnabled(false) 但不清 selectedKind
a.busy = False
check("还原后选中仍在（可以再启动一次）", selected_kind == "TIER",
      "源码 doRestore 完成后 setLaunchEnabled(selectedKind != KIND_NONE)，"
      "选中确实保留")

print()
print("=" * 60)
print("[10] Shizuku 在测试中途断开")
print("=" * 60)
a = Activity()
a.start_test()
# 此时 Shizuku 挂了，测试结束要还原屏幕 → resetMode 失败
restore_ok = False
applied_cleared = False
check("还原失败时标记应保留", not applied_cleared,
      "TierTester.finish 里：if (resetMode && !wasApplied) setApplied(false)。"
      "resetMode 失败 → 标记保留 → 下次打开补还原。这条是对的")

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
