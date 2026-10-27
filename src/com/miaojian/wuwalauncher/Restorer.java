package com.miaojian.wuwalauncher;

import android.content.Context;

/**
 * 还原。
 *
 * 两条触发路径，都调这里：
 *   1. 主界面「还原显示」按钮
 *   2. 常驻通知上的「还原」按钮
 *
 * 判断「需不需要还原」不靠自己的标记，而是读系统真实状态：
 *   分辨率 → 看有没有 Override
 *   导航/旋转 → 跟快照比对（系统里没有「被改过」的痕迹）
 *
 * 这样即使 App 数据被清空，也能正确识别出屏幕还停在超高分辨率。
 */
public final class Restorer {

    private Restorer() {
    }

    /** 检测结果 */
    public static final class Status {
        /** 分辨率被改过 */
        public final boolean sizeChanged;
        /** 导航被改过 */
        public final boolean navChanged;
        /** 旋转设置被改过 */
        public final boolean rotationChanged;

        Status(boolean size, boolean nav, boolean rot) {
            this.sizeChanged = size;
            this.navChanged = nav;
            this.rotationChanged = rot;
        }

        /** 有任何一项需要还原 */
        public boolean needsRestore() {
            return sizeChanged || navChanged || rotationChanged;
        }

        /** 用于日志与界面提示 */
        public String describe() {
            if (!needsRestore()) return "当前已是原生状态";
            StringBuilder sb = new StringBuilder("待还原：");
            if (sizeChanged) sb.append("分辨率 ");
            if (navChanged) sb.append("导航 ");
            if (rotationChanged) sb.append("旋转 ");
            return sb.toString().trim();
        }
    }

    /**
     * 检测当前是否需要还原。
     * 需要 shell 权限才能读 Override，所以要放在后台线程调用。
     */
    public static Status inspect(Context c) {
        // 旋转走 Settings.System，读写都不需要 shell 身份，
        // 所以先在 Shell 检查之前算出来 —— 高清档在无 Shizuku 时也会开自动旋转，
        // 退出时必须能把它还原回去。
        boolean rotChanged = false;
        if (Prefs.hasSnapshot(c)) {
            int curAccel = DisplayOps.readRotationSetting(
                    c, DisplayOps.KEY_ACCEL, 1);
            int curUser = DisplayOps.readRotationSetting(
                    c, DisplayOps.KEY_USER, 0);
            rotChanged = curAccel != Prefs.accel(c) || curUser != Prefs.user(c);
        }

        if (!Shell.ready()) {
            return new Status(false, false, rotChanged);
        }

        boolean sizeChanged = DisplayOps.hasAnyOverride(null);

        boolean navChanged = false;

        // 导航只能跟快照比对，且读 navigation_mode 需要 shell。
        // 没有快照就不知道原始值，此时不乱动 —— 宁可少还一项，
        // 也不能拿默认值硬套把用户设置改错。
        if (Prefs.hasSnapshot(c)) {
            int curNav = readSecure(c, "navigation_mode", 2);
            navChanged = curNav != Prefs.nav(c);
        }

        return new Status(sizeChanged, navChanged, rotChanged);
    }

    private static int readSecure(Context c, String key, int def) {
        try {
            return android.provider.Settings.Secure.getInt(
                    c.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 执行还原。
     *
     * @param sync true=当前线程直接跑（服务即将销毁时用），false=新线程
     */
    public static void restoreAll(Context c, Shell.Log log, boolean sync) {
        final Context app = c.getApplicationContext();
        final Shell.Log l = log;

        Runnable job = new Runnable() {
            public void run() {
                Status st = inspect(app);
                if (!st.needsRestore()) {
                    if (l != null) l.line(">> 已经是原生状态，无需还原");
                    Prefs.clear(app);
                    GuardService.stopGuard(app);
                    return;
                }

                if (l != null) l.line(">> " + st.describe());

                // 先撤悬浮窗，再改显示
                RotationOverlay.hide();

                boolean ok = true;
                if (st.sizeChanged) {
                    ok = DisplayOps.resetMode(l);
                }
                if (st.navChanged) {
                    ok &= DisplayOps.restoreNavigation(l, Prefs.nav(app));
                }
                if (st.rotationChanged) {
                    // 旋转走系统设置，缺权限时写不进去，属尽力而为
                    DisplayOps.putRotationSetting(
                            app, DisplayOps.KEY_ACCEL, Prefs.accel(app));
                    DisplayOps.putRotationSetting(
                            app, DisplayOps.KEY_USER, Prefs.user(app));
                }

                if (ok) {
                    Prefs.clear(app);
                    // 必须走 stopGuard：前台服务的通知只能由服务自己撤
                    GuardService.stopGuard(app);
                    if (l != null) l.line(">> 还原完成");
                } else if (l != null) {
                    // 失败就保留记录与通知，用户还能再点一次
                    l.line(">> 还原未完成，请检查 Shizuku 后重试");
                }
            }
        };

        if (sync) {
            job.run();
        } else {
            new Thread(job, "wuwa-restore").start();
        }
    }
}
