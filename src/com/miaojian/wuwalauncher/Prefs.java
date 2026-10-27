package com.miaojian.wuwalauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 系统状态快照。
 *
 * 记录改动前的原始状态，还原时按这份记录回滚，而不是硬套某个默认值。
 *
 * 两个关键约束：
 *
 * 1. 只在「干净状态」下记录。
 *    如果屏幕已经有 Override（上次没还原），此时记下的分辨率就是被改过的值，
 *    后面再拿它去还原等于没还原。所以有 Override 时不覆盖已有记录。
 *
 * 2. 快照可能丢（清数据、卸载重装）。
 *    此时只还原能确定的部分（分辨率看 Override），导航与旋转不乱动 ——
 *    宁可少还原一项，也不能猜错把用户的设置改乱。
 */
public final class Prefs {

    private static final String FILE = "wuwa_state";

    private static final String K_CAPTURED = "snapshot_captured";
    private static final String K_NAV = "base_nav";
    private static final String K_ACCEL = "base_accel";
    private static final String K_USER = "base_user";

    /** 记录时是否处于干净状态（屏幕无 Override） */
    private static final String K_CLEAN = "snapshot_clean";

    /** 当前是否处于「已改动」状态 */
    private static final String K_APPLIED = "applied";

    /** 是否请求把还原通知提升为流体云胶囊。默认关闭：多数机型不认这套接口 */
    private static final String K_LIVE = "live_notification";

    private Prefs() {
    }

    private static SharedPreferences of(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------- 快照 ----------------

    public static boolean hasSnapshot(Context c) {
        return of(c).getBoolean(K_CAPTURED, false);
    }

    /** 快照是否来自干净状态。不干净的话分辨率那一项不可信 */
    public static boolean isCleanSnapshot(Context c) {
        return of(c).getBoolean(K_CLEAN, false);
    }

    /**
     * 记录原始状态。
     *
     * @param clean 记录时屏幕是否无 Override。非干净状态只记导航与旋转，
     *              分辨率那一项交给 Override 判断
     */
    public static void snapshot(Context c, int nav, int accel, int user, boolean clean) {
        of(c).edit()
                .putInt(K_NAV, nav)
                .putInt(K_ACCEL, accel)
                .putInt(K_USER, user)
                .putBoolean(K_CLEAN, clean)
                .putBoolean(K_CAPTURED, true)
                .apply();
    }

    public static int nav(Context c) {
        return of(c).getInt(K_NAV, 2);
    }

    public static int accel(Context c) {
        return of(c).getInt(K_ACCEL, 1);
    }

    public static int user(Context c) {
        return of(c).getInt(K_USER, 0);
    }

    // ---------------- 改动标记 ----------------

    public static boolean isApplied(Context c) {
        return of(c).getBoolean(K_APPLIED, false);
    }

    public static void setApplied(Context c, boolean applied) {
        of(c).edit().putBoolean(K_APPLIED, applied).apply();
    }

    /** 流体云胶囊开关，默认关闭 */
    public static boolean isLiveNotificationEnabled(Context c) {
        return of(c).getBoolean(K_LIVE, false);
    }

    public static void setLiveNotificationEnabled(Context c, boolean enabled) {
        of(c).edit().putBoolean(K_LIVE, enabled).apply();
    }

    /** 清空整份记录（还原成功后调用） */
    public static void clear(Context c) {
        of(c).edit().clear().apply();
    }
}
