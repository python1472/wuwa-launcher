package com.miaojian.wuwalauncher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * 运行期权限检查与跳转。
 *
 * 两个权限不是「安装时授予」的，必须用户手动去系统页面开：
 *   SYSTEM_ALERT_WINDOW      → 方向控制悬浮窗
 *   WRITE_SETTINGS           → 写 Settings.System（锁旋转）
 *
 * 缺了这两个，功能会静默失效（putInt 返回 false / addView 抛异常），
 * 所以必须显式检查并引导。
 */
public final class Permissions {

    private Permissions() {
    }

    /** 能否挂悬浮窗 */
    public static boolean hasOverlay(Context c) {
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 能否写 Settings.System */
    public static boolean hasWriteSettings(Context c) {
        try {
            return Settings.System.canWrite(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳转「显示在其他应用上层」授权页 */
    public static void openOverlaySettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored2) {
            }
        }
    }

    /** 跳转「修改系统设置」授权页 */
    public static void openWriteSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored2) {
            }
        }
    }
}
