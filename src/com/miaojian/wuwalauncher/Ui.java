package com.miaojian.wuwalauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面构建工具。
 *
 * 项目不使用 XML 资源（构建链里没有 aapt2），所有视图用代码拼。
 * 这里集中放尺寸换算、圆角背景、文字样式，避免各页面重复写。
 */
public final class Ui {

    // ---- 配色 ----
    public static final int BG = 0xFF0E0E12;
    public static final int CARD = 0xFF17171D;
    public static final int CARD_ON = 0x22C24A34;
    public static final int LINE = 0xFF24242C;
    public static final int TEXT = 0xFFF0EEEA;
    public static final int TEXT_SUB = 0xFFD9D7D2;
    public static final int DIM = 0xFF8A8A93;
    public static final int FAINT = 0xFF63636D;
    public static final int ACCENT = 0xFFC24A34;
    public static final int GREEN = 0xFF4ADE80;
    public static final int AMBER = 0xFFE8C46A;
    public static final int RED = 0xFFE06A5A;
    public static final int LOG_TEXT = 0xFF9FE8B0;
    public static final int DISABLED_TEXT = 0xFF4A4A54;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable bg(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    public static TextView text(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView mono(Context c, String s, int color, float sp) {
        TextView t = text(c, s, color, sp, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    /** 竖排两行文字：主标题 + 副说明 */
    public static LinearLayout twoLines(Context c, TextView title, TextView desc, int gapDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(title);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(c, gapDp);
        box.addView(desc, p);
        return box;
    }

    /** 居中文字按钮 */
    public static TextView button(Context c, String label, int textColor,
                                  int fill, int stroke, int radiusDp,
                                  int padH, int padV) {
        TextView t = text(c, label, textColor, 12.5f, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(bg(c, fill, stroke, radiusDp));
        t.setPadding(dp(c, padH), dp(c, padV), dp(c, padH), dp(c, padV));
        return t;
    }

    /** 小圆角标签（风险提示等） */
    public static TextView chip(Context c, String label, int textColor, int bgColor) {
        TextView t = text(c, label, textColor, 10f, false);
        t.setBackground(bg(c, bgColor, 0, 6));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    public static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
