package com.miaojian.wuwalauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 常驻还原通知，同时尝试显示为状态栏胶囊（ColorOS 叫流体云）。
 *
 * 只在「有东西需要还原」时存在，还原完成后立刻取消。
 *
 * ===== 实况通知的硬性条件 =====
 *
 * 系统判断一条通知能否提升，看的是 hasPromotableCharacteristics()，
 * 它要求下面几项同时满足（缺一项都不会显示成胶囊）：
 *
 *   1. 带 FLAG_PROMOTED_ONGOING           → setRequestPromotedOngoing(true)
 *   2. 有 EXTRA_SHORT_CRITICAL_TEXT       → setShortCriticalText(...)
 *   3. 样式必须是 ProgressStyle           → 不能用 BigTextStyle
 *   4. 进度条必须真的设了（max 不为 0）    → setProgress(100, 100, false)
 *
 * 第 3、4 条最容易漏：只写标题和正文的普通通知，即使带了提升标志，
 * 系统也认为它「不具备可提升特征」，胶囊不会出现。
 *
 * 参考实现：github.com/Venompool888/FluidCapsule（同为 Android 16 提升通知路线）
 *
 * 另外两个影响观感的点：
 *   · 渠道重要性至少 DEFAULT，LOW 不会显示胶囊
 *   · 渠道重要性创建后不可改，调整时要换新的 CHANNEL_ID
 */
public final class RestoreNotification {

    /** 渠道 ID 变更是为了刷新重要性，见类注释最后一条 */
    private static final String CHANNEL_ID = "wuwa_restore_v3";
    private static final int NOTIFY_ID = 2001;

    /** 胶囊上显示的短文字，状态栏空间有限，越短越好 */
    static final String SHORT_TEXT = "高分辨率";

    static final String ACTION_RESTORE = "com.miaojian.wuwalauncher.RESTORE";

    private RestoreNotification() {
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager m = nm(c);
            if (m == null) return;
            if (m.getNotificationChannel(CHANNEL_ID) != null) return;

            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "显示还原", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("游戏以高分辨率运行时，可从这里一键还原");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            m.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    public static Notification build(Context c, String title, String text) {
        ensureChannel(c);

        Intent open = new Intent(c, LauncherActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent restore = new Intent(c, RestoreActionReceiver.class);
        restore.setAction(ACTION_RESTORE);
        PendingIntent restorePi = PendingIntent.getBroadcast(c, 1, restore,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);

        b.setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        null, "还原", restorePi).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        // 实况通知的四个条件，逐个满足
        // 提升为流体云胶囊是可选项，默认关闭：
        // 多数 ColorOS 版本不认标准接口，开了也只是普通通知
        if (Prefs.isLiveNotificationEnabled(c)) {
            applyPromoted(c, b);
        }

        Notification n = b.build();

        if (Prefs.isLiveNotificationEnabled(c)) {
            try {
                java.lang.reflect.Method m = Notification.class
                        .getMethod("hasPromotableCharacteristics");
                diag(c, "hasPromotableCharacteristics=" + m.invoke(n));
            } catch (Throwable t) {
                diag(c, "hasPromotableCharacteristics 不可用: " + t);
            }

            NotificationManager mgr = nm(c);
            if (mgr != null) {
                try {
                    java.lang.reflect.Method m = NotificationManager.class
                            .getMethod("canPostPromotedNotifications");
                    diag(c, "canPostPromotedNotifications=" + m.invoke(mgr));
                } catch (Throwable t) {
                    diag(c, "canPostPromotedNotifications 不可用: " + t);
                }
            }
            diag(c, "权限 allowed=" + isPromotionAllowed(c));
        }

        return n;
    }

    private static final String TAG = "WuwaLive";

    /**
     * 把一条诊断写进外部文件，便于排查。
     *
     * 不用 logcat：调试期间 logcat 被其它进程的大量输出冲刷，
     * 应用自己打的日志常常读不到。外部目录 /sdcard/Android/data/<包名>/files
     * 用 shell 身份可以直接读。
     */
    private static void diag(Context c, String s) {
        try {
            java.io.File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, "live.txt"), true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss")
                    .format(new java.util.Date()) + " " + s + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 让通知具备「可提升特征」。
     *
     * ProgressStyle 与 setRequestPromotedOngoing 都是 API 36 才有的，
     * 构建用的 android.jar 是 API 34，只能反射。调不到就退化为普通通知，
     * 不影响还原功能。
     */
    private static void applyPromoted(Context c, Notification.Builder b) {
        // 条件 3、4：样式必须是 ProgressStyle，且进度条 max 不为 0。
        // 用 100/100 而不是不确定进度 —— 不确定进度的 max 是 0，
        // 系统会认为「没有设置进度」，依然不算可提升。
        boolean styleOk = false;
        try {
            Class<?> styleClass = Class.forName("android.app.Notification$ProgressStyle");

            Object style = null;
            // 不同版本构造参数不同，先试无参，再试带颜色参数
            try {
                style = styleClass.getConstructor().newInstance();
                diag(c, "ProgressStyle 无参构造 OK");
            } catch (Throwable e1) {
                diag(c, "ProgressStyle 无参构造失败: " + e1);
                try {
                    style = styleClass.getConstructor(int.class).newInstance(0);
                    diag(c, "ProgressStyle(int) 构造 OK");
                } catch (Throwable e2) {
                    diag(c, "ProgressStyle(int) 构造失败: " + e2);
                }
            }

            if (style != null) {
                try {
                    styleClass.getMethod("setStyledByProgress", boolean.class)
                            .invoke(style, false);
                } catch (Throwable e) {
                    diag(c, "setStyledByProgress 失败: " + e);
                }
                try {
                    styleClass.getMethod("setProgress", int.class).invoke(style, 100);
                    diag(c, "ProgressStyle.setProgress OK");
                } catch (Throwable e) {
                    diag(c, "ProgressStyle.setProgress 失败: " + e);
                }

                b.setProgress(100, 100, false);
                b.setStyle((Notification.Style) style);
                styleOk = true;
                diag(c, "setStyle(ProgressStyle) 完成");
            }
        } catch (Throwable t) {
            diag(c, "ProgressStyle 反射失败: " + t);
        }

        if (!styleOk) {
            try {
                b.setStyle(new Notification.BigTextStyle());
                diag(c, "退化为 BigTextStyle");
            } catch (Throwable ignored) {
            }
        }

        // 条件 1：请求提升（反射方法 + extras 双保险）
        try {
            Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(b, true);
        } catch (Throwable t) {
            diag(c, "setRequestPromotedOngoing 失败: " + t);
        }

        // 条件 2：短文字（反射方法 + extras 双保险，键名是公开常量）
        boolean reflectOk = false;
        try {
            Notification.Builder.class.getMethod("setShortCriticalText", CharSequence.class)
                    .invoke(b, SHORT_TEXT);
            reflectOk = true;
        } catch (Throwable t) {
            diag(c, "setShortCriticalText 失败: " + t);
        }

        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            if (!reflectOk) {
                extras.putCharSequence("android.shortCriticalText", SHORT_TEXT);
            }
            b.addExtras(extras);
        } catch (Throwable ignored) {
        }
    }

    /** 这条通知是否真的具备可提升特征（用于日志诊断） */
    public static boolean hasPromotableCharacteristics(Context c, String title, String text) {
        try {
            Notification n = build(c, title, text);
            java.lang.reflect.Method m = Notification.class
                    .getMethod("hasPromotableCharacteristics");
            Object r = m.invoke(n);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统是否允许本应用发实况通知 */
    public static boolean canPostPromoted(Context c) {
        try {
            NotificationManager m = nm(c);
            if (m == null) return false;
            java.lang.reflect.Method method = NotificationManager.class
                    .getMethod("canPostPromotedNotifications");
            Object r = method.invoke(m);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用 Shizuku 的 shell 身份打开实况通知权限。
     *
     * 这个 appop 默认是 ignore，不打开的话系统不会渲染胶囊。
     * 系统设置里的入口各机型位置不同（ColorOS 叫流体云），
     * 既然本 App 本来就需要 Shizuku，顺手打开更省事。
     */
    public static boolean enablePromotionByShell(Shell.Log log) {
        if (!Shell.ready()) return false;
        return Shell.ok(
                "cmd appops set com.miaojian.wuwalauncher "
                        + "POST_PROMOTED_NOTIFICATIONS allow", log);
    }

    /**
     * 打开系统的「流体云」设置页，让用户自己开关。
     *
     * realme / OPPO 的流体云是自家白名单机制，第三方应用能否显示胶囊
     * 由系统决定，标准接口只能「请求」，不能强制。
     * 这个页面是系统提供的正规入口，比改系统数据库安全得多。
     */
    public static boolean openFluidCloudSettings(Context c) {
        // 优先打开主流设置主页
        String[][] targets = {
                {"oplus.fluid.seeding.settings.main",
                        "com.oplus.notificationmanager",
                        "com.oplus.notificationmanager.fluidseedingsettings.activity.FluidSeedingSettingMainActivity"},
                {"com.android.settings.MANUFACTURER_APPLICATION_SETTING",
                        "com.oplus.notificationmanager",
                        "com.oplus.notificationmanager.fluidseedingsettings.activity.FluidSeedingSettingMainActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent(t[0]);
                i.setClassName(t[1], t[2]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        // 退而求其次：打开本应用的通知设置页
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", c.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** appop 是否已允许 */
    public static boolean isPromotionAllowed(Context c) {
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            return aom.unsafeCheckOpNoThrow(
                    "android:post_promoted_notifications",
                    android.os.Process.myUid(), c.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void show(Context c, String title, String text) {
        try {
            NotificationManager m = nm(c);
            if (m != null) m.notify(NOTIFY_ID, build(c, title, text));
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context c) {
        try {
            NotificationManager m = nm(c);
            if (m != null) m.cancel(NOTIFY_ID);
        } catch (Throwable ignored) {
        }
    }

    public static int id() {
        return NOTIFY_ID;
    }
}
