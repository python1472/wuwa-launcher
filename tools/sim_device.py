#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android 显示状态模拟器 —— 用来在沙箱里跑通 App 的流程逻辑。

它实现 App 会调用的那些 shell 命令对应的系统行为：

    wm size / wm size WxH / wm size reset
    wm density / wm density N / wm density reset
    settings put secure navigation_mode N
    cmd overlay enable-exclusive --category <name>
    settings put system accelerometer_rotation / user_rotation

能验证的东西：
    · 档位数值算得对不对（分辨率、密度、风险分级）
    · 还原是否完整 —— 有没有残留 override 或导航模式没回去
    · 标记逻辑 —— 什么情况下该清、什么情况下必须留

验证不了的东西（只能真机）：
    · 悬浮窗能否挂上、方向是否真的被压住
    · Shizuku 授权、真实渲染效果、UI 表现
"""

import math


def jround(x):
    """Java 的 Math.round 对正数等价于 floor(x + 0.5)"""
    return int(math.floor(x + 0.5))


class Device:
    """一台可以改分辨率、改密度、切导航模式的假手机"""

    def __init__(self, w=1280, h=2800, density=560, nav=2, accel=1, user_rot=0):
        self.phys_w = w
        self.phys_h = h
        self.phys_density = density
        self.nav = nav
        self.accel = accel
        self.user_rot = user_rot
        self.overlay = "gestural"

    # ---------- 状态查询 ----------
    def state(self):
        return {
            "phys": "%dx%d" % (self.phys_w, self.phys_h),
            "phys_density": self.phys_density,
            "nav": self.nav,
            "accel": self.accel,
            "user_rot": self.user_rot,
            "overlay": self.overlay,
        }

    def wm_size_output(self):
        return "Physical size: %dx%d" % (self.phys_w, self.phys_h)

    def wm_density_output(self):
        return "Physical density: %d" % self.phys_density


class ShellSim:
    """模拟 Shizuku 执行 shell，返回 (成功, stdout)"""

    def __init__(self, device, available=True, fail_wm=False):
        self.dev = device
        self.available = available
        self.fail_wm = fail_wm
        self.log = []

    def exec(self, cmd):
        self.log.append(cmd)

        if not self.available:
            return False, ""

        cmd = cmd.strip()

        if cmd == "wm size":
            return True, self.dev.wm_size_output()
        if cmd.startswith("wm size "):
            if self.fail_wm:
                return False, "error"
            arg = cmd[len("wm size "):]
            if arg == "reset":
                return True, ""
            w, h = arg.split("x")
            # 真实系统会做一次 clamp：宽高比不同也不报错，但这里只记录
            return True, "%dx%d" % (int(w), int(h))
        if cmd == "wm density":
            return True, self.dev.wm_density_output()
        if cmd.startswith("wm density "):
            if self.fail_wm:
                return False, "error"
            arg = cmd[len("wm density "):]
            if arg == "reset":
                return True, ""
            return True, arg
        if cmd.startswith("cmd overlay enable-exclusive --category "):
            name = cmd.split()[-1]
            return True, ""
        if cmd.startswith("settings put secure navigation_mode "):
            return True, ""
        if cmd.startswith("settings put system "):
            return True, ""
        if cmd.startswith("pidof "):
            return True, ""
        return True, ""


class AppModel:
    """
    把 App 的流程按源码逻辑复刻出来。
    命令文本与 LauncherActivity / DisplayOps / Restorer 里的一致。
    """

    def __init__(self, shell):
        self.shell = shell
        self.applied = False
        self.nav_changed = False
        self.rot_changed = False
        self.snap = {}
        self.captured = False
        self.overlay_shown = False

    # ---- 档位计算，复刻 DisplayOps.buildMode ----
    @staticmethod
    def build_mode(pw, ph, pd, scale):
        w = jround(pw * scale)
        h = jround(ph * scale)
        d = jround(pd * scale)
        mp = w * h / 1_000_000.0
        if mp <= 7.5:
            risk = 0
        elif mp <= 10.0:
            risk = 1
        elif mp <= 13.0:
            risk = 2
        else:
            risk = 3
        return {"label": "%.1f×" % scale, "w": w, "h": h, "d": d,
                "mp": mp, "risk": risk}

    # ---- 快照：只在没有未还原改动时记录 ----
    def snapshot(self, nav, accel, user_rot):
        if not self.applied:
            self.snap = {"nav": nav, "accel": accel, "user_rot": user_rot}
            self.captured = True
            return True
        return False

    # ---- 启动流程 ----
    def apply_and_launch(self, kind, mode=None, panel=None, shizuku_ok=True):
        ok = True
        nav_changed = False
        rot_changed = False

        self.applied = True

        if shizuku_ok:
            if kind == "hd":
                ok = self.apply_mode(panel["w"], panel["h"], panel["d"])
            elif kind in ("tier", "custom") and mode:
                ok = self.apply_mode(mode["w"], mode["h"], mode["d"])

            if kind in ("tier", "custom"):
                ok = self.switch_three_button() and ok
                nav_changed = True
        # 原生档（kind == "native"）什么都不做

        rot_ok = self.enable_auto_rotate()
        rot_changed = rot_ok

        # 累加，不覆盖
        self.nav_changed = self.nav_changed or nav_changed
        self.rot_changed = self.rot_changed or rot_changed
        return ok

    def apply_mode(self, w, h, d):
        a = self.shell.exec("wm size %dx%d" % (w, h))[0]
        b = self.shell.exec("wm density %d" % d)[0]
        if a:
            self.shell.dev.phys_w = w
            self.shell.dev.phys_h = h
        if b:
            self.shell.dev.phys_density = d
        return a and b

    def switch_three_button(self):
        a = self.shell.exec(
            "cmd overlay enable-exclusive --category "
            "com.android.internal.systemui.navbar.threebutton")[0]
        b = self.shell.exec("settings put secure navigation_mode 0")[0]
        if b:
            self.shell.dev.nav = 0
            self.shell.dev.overlay = "threebutton"
        return a and b

    def enable_auto_rotate(self):
        ok, _ = self.shell.exec("settings put system accelerometer_rotation 1")
        if ok:
            self.shell.dev.accel = 1
        return ok

    # ---- 还原，复刻 Restorer.restoreAll ----
    def restore(self):
        if not self.applied:
            return True

        self.overlay_shown = False

        ok = self.reset_mode()

        if self.nav_changed and self.captured:
            ok = self.restore_navigation(self.snap["nav"]) and ok

        if self.rot_changed and self.captured:
            self.shell.exec("settings put system accelerometer_rotation %d"
                            % self.snap["accel"])
            self.shell.exec("settings put system user_rotation %d"
                            % self.snap["user_rot"])
            self.shell.dev.accel = self.snap["accel"]
            self.shell.dev.user_rot = self.snap["user_rot"]

        if ok:
            self.applied = False
            self.nav_changed = False
            self.rot_changed = False
        return ok

    def reset_mode(self):
        a = self.shell.exec("wm size reset")[0]
        b = self.shell.exec("wm density reset")[0]
        if a:
            self.shell.dev.phys_w = 1280
            self.shell.dev.phys_h = 2800
        if b:
            self.shell.dev.phys_density = 560
        return a and b

    def restore_navigation(self, nav):
        overlay = ("com.android.internal.systemui.navbar.threebutton"
                   if nav == 0 else
                   "com.android.internal.systemui.navbar.gestural")
        a = self.shell.exec("cmd overlay enable-exclusive --category " + overlay)[0]
        b = self.shell.exec("settings put secure navigation_mode %d" % nav)[0]
        if b:
            self.shell.dev.nav = nav
            self.shell.dev.overlay = ("threebutton" if nav == 0 else "gestural")
        return a and b
