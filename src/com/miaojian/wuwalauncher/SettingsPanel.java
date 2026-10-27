package com.miaojian.wuwalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设置面板。
 *
 * 从主界面右上角进入，内容以底部弹出层呈现。
 *
 * 目前只有一项：修复系统动画异常。
 * 这个问题在改过分辨率后必现 —— 启动动画会先按旧边界展开成圆角小窗，
 * 再突然铺满全屏。根治要靠重启桌面进程，让桌面的 Launcher 重新
 * 读取显示配置（实测有效，重启后圆角就正常了）。
 */
public final class SettingsPanel {

    /** 桌面进程包名。realme / ColorOS 用的是 AOSP 的 Launcher */
    private static final String LAUNCHER_PKG = "com.android.launcher";
    /** 部分机型改名，作为兜底再试一次 */
    private static final String[] LAUNCHER_ALTERNATIVES = {
            "com.android.launcher",
            "com.android.launcher3",
            "com.oppo.launcher",
            "com.coloros.launcher",
    };

    private SettingsPanel() {
    }

    /** 弹出设置面板 */
    public static void show(final Activity activity, final Shell.Log log,
                            final DisplayOps.Panel panel,
                            final Runnable onAnimationFixed) {
        final Context c = activity;

        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.bg(c, 0xFF17171D, 0xFF2B2B34, 20));
        sheet.setPadding(Ui.dp(c, 20), Ui.dp(c, 20),
                Ui.dp(c, 20), Ui.dp(c, 20));

        // 标题
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(c, "设置", Ui.TEXT, 17f, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = Ui.text(c, "关闭", Ui.FAINT, 12.5f, false);
        header.addView(close);
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ============ 系统分组 ============
        TextView section = Ui.text(c, "系统", Ui.FAINT, 11.5f, false);
        LinearLayout.LayoutParams secp = new LinearLayout.LayoutParams(-1, -2);
        secp.topMargin = Ui.dp(c, 20);
        secp.bottomMargin = Ui.dp(c, 9);
        sheet.addView(section, secp);

        // 选项：流体云 / 实况通知设置（标记为测试功能）
        LinearLayout fluidItem = new LinearLayout(c);
        fluidItem.setOrientation(LinearLayout.HORIZONTAL);
        fluidItem.setGravity(Gravity.CENTER_VERTICAL);
        fluidItem.setBackground(Ui.bg(c, Ui.CARD, Ui.LINE, 13));
        fluidItem.setPadding(Ui.dp(c, 15), Ui.dp(c, 14),
                Ui.dp(c, 15), Ui.dp(c, 14));

        LinearLayout fluidTitleRow = new LinearLayout(c);
        fluidTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        fluidTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        fluidTitleRow.addView(Ui.text(c, "流体云 / 实况通知", 0xFFD9D7D2, 14.5f, true));
        TextView testBadge = Ui.chip(c, "测试", 0xFFE8C46A, 0x1AE8C46A);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.leftMargin = Ui.dp(c, 7);
        fluidTitleRow.addView(testBadge, badgeLp);

        TextView ft2 = Ui.text(c,
                "让还原通知显示成状态栏胶囊\n需系统支持，默认关闭",
                Ui.FAINT, 11.5f, false);

        LinearLayout fluidTexts = new LinearLayout(c);
        fluidTexts.setOrientation(LinearLayout.VERTICAL);
        fluidTexts.addView(fluidTitleRow);
        LinearLayout.LayoutParams ft2p = new LinearLayout.LayoutParams(-1, -2);
        ft2p.topMargin = Ui.dp(c, 4);
        fluidTexts.addView(ft2, ft2p);

        fluidItem.addView(fluidTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        fluidItem.addView(Ui.text(c, "›", Ui.FAINT, 18f, false));
        sheet.addView(fluidItem, new LinearLayout.LayoutParams(-1, -2));

        // 选项：系统动画异常
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(Ui.bg(c, Ui.CARD, Ui.LINE, 13));
        item.setPadding(Ui.dp(c, 15), Ui.dp(c, 14),
                Ui.dp(c, 15), Ui.dp(c, 14));

        TextView t1 = Ui.text(c, "系统动画异常", 0xFFD9D7D2, 14.5f, true);
        TextView t2 = Ui.text(c,
                "打开应用时出现圆角小窗再突然铺满\n点击重启桌面进程修复",
                Ui.FAINT, 11.5f, false);
        item.addView(Ui.twoLines(c, t1, t2, 4),
                new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = Ui.text(c, "›", Ui.FAINT, 18f, false);
        item.addView(arrow);

        sheet.addView(item, new LinearLayout.LayoutParams(-1, -2));

        // 说明
        TextView footnote = Ui.text(c,
                "改过分辨率后该问题必现，重启桌面后圆角恢复正常。\n"
                        + "重启期间桌面图标会短暂消失，属正常现象。",
                Ui.FAINT, 10.5f, false);
        footnote.setLineSpacing(Ui.dp(c, 3), 1f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = Ui.dp(c, 12);
        sheet.addView(footnote, fp);

        final AlertDialog.Builder builder = new AlertDialog.Builder(c);
        builder.setView(sheet);
        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    Ui.bg(c, 0x00000000, 0, 0));
        }

        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        fluidItem.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                showLiveNotificationDialog(c, log, testBadge == null ? null : sheet);
            }
        });


        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                confirmRestartLauncher(activity, log, onAnimationFixed);
            }
        });

        dialog.show();
    }

    /**
     * 流体云胶囊的开关对话框。
     *
     * 这是实验性功能，默认关闭。开启后通知会请求提升为状态栏胶囊，
     * 但能否真的显示由系统决定 —— 实测多数 ColorOS 版本不认这套标准接口，
     * 打开后依然只是普通通知。
     */
    private static void showLiveNotificationDialog(final Context c, final Shell.Log log,
                                                   View anchor) {
        final boolean current = Prefs.isLiveNotificationEnabled(c);

        StringBuilder msg = new StringBuilder();
        msg.append("开启后，还原通知会请求显示为状态栏胶囊（流体云）。\n\n")
                .append("当前状态：").append(current ? "已开启" : "已关闭").append("\n\n")
                .append("注意：能否显示由系统决定。已知 ColorOS 16.0.0.500 及以上才支持，")
                .append("更早版本即使打开也只会显示为普通通知。");

        new AlertDialog.Builder(c)
                .setTitle("流体云胶囊（测试）")
                .setMessage(msg.toString())
                .setPositiveButton(current ? "关闭" : "开启",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Prefs.setLiveNotificationEnabled(c, !current);
                                if (log != null) {
                                    log.line("[流体云] 已" + (!current ? "开启" : "关闭")
                                            + "，下次发通知时生效");
                                }
                            }
                        })
                .setNeutralButton("打开系统设置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = RestoreNotification.openFluidCloudSettings(c);
                        if (log != null) {
                            log.line(ok ? "[流体云] 已打开系统设置页"
                                    : "[流体云] 打不开系统设置页，请手动查找");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重启桌面前的二次确认 */
    private static void confirmRestartLauncher(final Activity activity,
                                               final Shell.Log log,
                                               final Runnable onDone) {
        new AlertDialog.Builder(activity)
                .setTitle("重启桌面进程？")
                .setMessage("用于修复「打开应用时先出现圆角小窗」的动画异常。\n\n"
                        + "重启期间桌面会短暂空白、图标重新加载，"
                        + "正在玩的游戏不受影响。")
                .setPositiveButton("重启桌面", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        restartLauncher(activity, log, onDone);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 真正执行：强制停止桌面包，系统会自动重新拉起 */
    private static void restartLauncher(Activity activity, Shell.Log log,
                                        final Runnable onDone) {
        final Context app = activity.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());

        if (!Shell.ready()) {
            if (log != null) log.line("[桌面] 需要 Shizuku 授权才能重启桌面进程");
            ui.post(new Runnable() {
                public void run() {
                    new AlertDialog.Builder(app)
                            .setTitle("需要 Shizuku 授权")
                            .setMessage("重启桌面进程需要 shell 权限，请先在主界面完成授权。")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
            return;
        }

        if (log != null) log.line("[桌面] 正在重启桌面进程…");

        new Thread(new Runnable() {
            public void run() {
                // 先找出真实在运行的桌面包名，再针对性重启
                final String target = findRunningLauncher(log);
                boolean ok = false;
                if (target != null) {
                    ok = Shell.ok("am force-stop " + target, log);
                }

                // 系统会自动拉起桌面，这里确保回到桌面
                Shell.exec("input keyevent KEYCODE_HOME", log);

                final boolean success = ok;
                final String pkg = target;
                ui.post(new Runnable() {
                    public void run() {
                        if (log != null) {
                            log.line(success
                                    ? "[桌面] 已重启 " + pkg + "，请再打开一个应用确认动画"
                                    : "[桌面] 重启失败，可尝试重启手机");
                        }
                        if (onDone != null) onDone.run();
                    }
                });
            }
        }, "wuwa-restart-launcher").start();
    }

    /** 从运行中的进程里找桌面包名 */
    private static String findRunningLauncher(Shell.Log log) {
        String out = Shell.exec("pm list packages", null);
        if (out != null) {
            for (String alt : LAUNCHER_ALTERNATIVES) {
                // pm list packages 输出形如 package:com.android.launcher
                if (out.contains("package:" + alt + "\n")
                        || out.endsWith("package:" + alt)) {
                    // 确认它在跑
                    String pid = Shell.exec("pidof " + alt, null);
                    if (pid != null && pid.trim().length() > 0) {
                        if (log != null) log.line("[桌面] 当前桌面：" + alt + "（pid " + pid.trim() + "）");
                        return alt;
                    }
                }
            }
        }
        // 兜底：默认用 AOSP 包名
        if (log != null) log.line("[桌面] 未识别到运行中的桌面，尝试默认包名");
        return LAUNCHER_PKG;
    }
}
