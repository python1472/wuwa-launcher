package com.miaojian.wuwalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 自定义渲染倍率选择器。
 *
 * 用滚轮选倍率（1.0× ~ 3.0×，0.1 一档），下方实时显示换算后的
 * 分辨率、密度与风险等级。确认后还要过一道免责声明，才交给主界面测试。
 *
 * 密度始终跟随倍率同比例缩放，这样界面元素大小不变 —— 这是必须的，
 * 只改尺寸不改密度会让整个界面忽大忽小。
 */
public final class CustomTierDialog {

    public interface Callback {
        void onPicked(DisplayOps.Mode mode);
    }

    private static final int DEFAULT_STEP = 4;   // 1.4×，和预设最安全档一致

    private CustomTierDialog() {
    }

    public static void show(final Activity activity, final DisplayOps.Panel panel,
                            final Callback callback) {
        final Context c = activity;

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.bg(c, 0xFF17171D, 0xFF2B2B34, 20));
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 22),
                Ui.dp(c, 20), Ui.dp(c, 16));

        box.addView(Ui.text(c, "自定义渲染倍率", Ui.TEXT, 17f, true),
                new LinearLayout.LayoutParams(-1, -2));

        TextView sub = Ui.text(c, "渲染分辨率 = 屏幕分辨率 × 倍率，密度同步缩放",
                Ui.FAINT, 11.5f, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(c, 5);
        box.addView(sub, sp);

        // ---- 滚轮 ----
        final String[] labels = new String[DisplayOps.CUSTOM_STEPS];
        for (int i = 0; i < DisplayOps.CUSTOM_STEPS; i++) {
            labels[i] = String.format("%.1f×", DisplayOps.CUSTOM_MIN + i * 0.1f);
        }

        final WheelPicker picker = new WheelPicker(c);
        picker.setItems(labels);
        picker.setSelected(DEFAULT_STEP);

        LinearLayout pickerWrap = new LinearLayout(c);
        pickerWrap.setGravity(Gravity.CENTER);
        pickerWrap.addView(picker, new LinearLayout.LayoutParams(
                Ui.dp(c, 190), Ui.dp(c, 200)));
        LinearLayout.LayoutParams pwp = new LinearLayout.LayoutParams(-1, -2);
        pwp.topMargin = Ui.dp(c, 6);
        box.addView(pickerWrap, pwp);

        // ---- 实时换算结果 ----
        LinearLayout preview = new LinearLayout(c);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setBackground(Ui.bg(c, 0xFF13131A, 0xFF26262E, 12));
        preview.setPadding(Ui.dp(c, 14), Ui.dp(c, 12),
                Ui.dp(c, 14), Ui.dp(c, 12));

        final TextView lineSize = Ui.text(c, "", 0xFFD9D7D2, 13f, true);
        final TextView lineDensity = Ui.text(c, "", Ui.FAINT, 11.5f, false);
        final TextView lineRisk = Ui.text(c, "", Ui.AMBER, 11.5f, false);
        preview.addView(lineSize);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = Ui.dp(c, 4);
        preview.addView(lineDensity, lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, -2);
        lp3.topMargin = Ui.dp(c, 6);
        preview.addView(lineRisk, lp3);

        LinearLayout.LayoutParams pvp = new LinearLayout.LayoutParams(-1, -2);
        pvp.topMargin = Ui.dp(c, 8);
        box.addView(preview, pvp);

        final DisplayOps.Mode[] current = new DisplayOps.Mode[]{null};

        final Runnable refresh = new Runnable() {
            public void run() {
                float scale = DisplayOps.CUSTOM_MIN + picker.getSelected() * 0.1f;
                DisplayOps.Mode mode = DisplayOps.buildMode(panel, scale);
                current[0] = mode;

                lineSize.setText(mode.sizeText());
                lineDensity.setText("密度 " + mode.density + "dpi（跟随倍率缩放）");
                lineRisk.setText(riskDescription(mode.risk));
                lineRisk.setTextColor(riskColor(mode.risk));
            }
        };

        picker.setOnChanged(new WheelPicker.OnChanged() {
            public void onChanged(int index) {
                refresh.run();
            }
        });
        refresh.run();

        // ---- 按钮 ----
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(c, 16);
        box.addView(bar, bp);

        TextView cancel = Ui.button(c, "取消", Ui.DIM, Ui.CARD, Ui.LINE, 12, 0, 12);
        TextView confirm = Ui.button(c, "下一步", 0xFFFFFFFF, Ui.ACCENT, 0, 12, 0, 12);

        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, -2, 1f);
        c1.rightMargin = Ui.dp(c, 9);
        bar.addView(cancel, c1);
        bar.addView(confirm, new LinearLayout.LayoutParams(0, -2, 1f));

        final AlertDialog dialog = new AlertDialog.Builder(c).setView(box).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        }

        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        confirm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final DisplayOps.Mode mode = current[0];
                if (mode == null) return;
                dialog.dismiss();
                confirmAndPick(activity, panel, mode, callback);
            }
        });

        dialog.show();
    }

    /**
     * 按倍率决定要不要弹免责声明。
     *
     * 1.6× 及以下直接进测试 —— 这些档位像素量在本机承受范围内容易跑通，
     * 每次都弹一遍只会拖慢流程。超过 1.6× 才需要用户明确知晓后果。
     */
    public static void confirmAndPick(final Activity activity, final DisplayOps.Panel panel,
                                     final DisplayOps.Mode mode, final Callback callback) {
        if (!mode.needsConfirm()) {
            callback.onPicked(mode);
            return;
        }
        showDisclaimer(activity, panel, mode, callback);
    }

    /** 免责声明。高风险倍率额外列出具体后果。 */
    private static void showDisclaimer(final Activity activity, final DisplayOps.Panel panel,
                                       final DisplayOps.Mode mode, final Callback callback) {
        double baseMp = panel.width * (double) panel.height / 1_000_000d;
        double ratio = mode.megapixels() / baseMp;

        StringBuilder msg = new StringBuilder();
        msg.append("将以 ").append(mode.sizeText())
                .append("（").append(mode.density).append("dpi）")
                .append(" 作为渲染分辨率，进行 ")
                .append(TierTester.SECONDS).append(" 秒测试。\n\n")
                .append("像素量为原生的 ").append(String.format("%.2f", ratio)).append(" 倍。\n\n");

        if (mode.risk >= DisplayOps.RISK_WARN) {
            msg.append("⚠ 该倍率属于「").append(mode.riskText()).append("」区间：\n");
            if (mode.risk >= DisplayOps.RISK_HIGH) {
                msg.append("· 显存占用可能超过本机可用内存\n");
                msg.append("· 进入大场景或加载副本时可能闪退\n");
                msg.append("· 若测试中途崩溃，屏幕可能停留在超高分辨率\n");
            } else {
                msg.append("· 复杂场景下可能出现掉帧\n");
                msg.append("· 长时间游玩发热明显\n");
            }
            msg.append("\n");
        }

        msg.append("测试结束会自动还原，不会永久改动设置。\n")
                .append("倍率越高画面越锐利，发热与耗电同步上升。\n\n")
                .append("以上风险已知晓后再继续。");

        new AlertDialog.Builder(activity)
                .setTitle("免责声明与风险提示")
                .setMessage(msg.toString())
                .setPositiveButton("我已知晓，继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        callback.onPicked(mode);
                    }
                })
                .setNegativeButton("返回重选", null)
                .show();
    }

    private static String riskDescription(int risk) {
        switch (risk) {
            case DisplayOps.RISK_SAFE:   return "稳妥 · 内存占用在安全范围";
            case DisplayOps.RISK_WARN:   return "有风险 · 复杂场景可能掉帧";
            case DisplayOps.RISK_HIGH:   return "风险高 · 进入大场景可能闪退";
            default:                     return "极易崩溃 · 超出本机内存承受范围";
        }
    }

    private static int riskColor(int risk) {
        switch (risk) {
            case DisplayOps.RISK_SAFE: return Ui.GREEN;
            case DisplayOps.RISK_DANGER: return Ui.RED;
            default: return Ui.AMBER;
        }
    }
}
