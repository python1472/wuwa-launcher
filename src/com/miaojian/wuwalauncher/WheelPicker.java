package com.miaojian.wuwalauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * 竖直滚轮选择器。
 *
 * ===== 为什么不用系统 NumberPicker =====
 *
 * NumberPicker 里显示当前值的是内部的一个 EditText（mInputText），
 * 滑动时露出的上下相邻项是另外两个子 View，它们的文字颜色跟随主题。
 * 深色界面上那个主题文字色是黑色 —— 于是只有初始值能看见，
 * 一滑动看到的全是黑字，等于没字。反射只能改 mInputText，改不到那两个子 View。
 *
 * 自己画就完全可控：颜色、字号、渐隐、中间项高亮都能自己定。
 *
 * ===== 交互 =====
 *
 * 拖动时即时跟手，松手吸附到最近一项。不做惯性滑动 ——
 * 只有二十来档，惯性反而容易滑过头，还得再拖回来。
 */
public class WheelPicker extends View {

    public interface OnChanged {
        void onChanged(int index);
    }

    /** 纵向可见多少项，中间那项是选中的 */
    private static final int VISIBLE = 5;
    private static final long FRAME_MS = 16;

    private String[] items = new String[0];
    private int selected;
    /** 以「项」为单位的滚动位置，0 表示第一项在正中 */
    private float offset;
    private boolean dragging;
    private float lastY;
    private float settleTarget;

    private OnChanged listener;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable settle = new Runnable() {
        public void run() {
            float diff = settleTarget - offset;
            if (Math.abs(diff) < 0.02f) {
                offset = settleTarget;
                int idx = Math.round(offset);
                if (idx != selected) {
                    selected = idx;
                    if (listener != null) listener.onChanged(idx);
                }
                invalidate();
                return;
            }
            offset += diff * 0.35f;
            invalidate();
            ui.postDelayed(this, FRAME_MS);
        }
    };

    public WheelPicker(Context c) {
        super(c);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        guide.setColor(0x33FFFFFF);
        guide.setStrokeWidth(Ui.dp(c, 1));
    }

    public void setItems(String[] values) {
        items = (values == null) ? new String[0] : values;
        if (selected >= items.length) {
            selected = Math.max(0, items.length - 1);
        }
        offset = selected;
        invalidate();
    }

    public void setSelected(int index) {
        if (items.length == 0) return;
        selected = Math.max(0, Math.min(index, items.length - 1));
        offset = selected;
        invalidate();
    }

    public int getSelected() {
        return selected;
    }

    public void setOnChanged(OnChanged l) {
        listener = l;
    }

    private float itemHeight() {
        int h = getHeight();
        return h <= 0 ? 1f : h / (float) VISIBLE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ui.removeCallbacks(settle);
                dragging = true;
                lastY = e.getY();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float dy = e.getY() - lastY;
                lastY = e.getY();
                offset -= dy / itemHeight();
                offset = Math.max(0f, Math.min(offset, Math.max(0, items.length - 1)));
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return true;
                dragging = false;
                settleTarget = Math.round(offset);
                settleTarget = Math.max(0f, Math.min(settleTarget,
                        Math.max(0, items.length - 1)));
                ui.post(settle);
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (items.length == 0 || getHeight() <= 0) return;

        float ih = itemHeight();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        // 两条分隔线，框出中间那一项
        float left = getWidth() * 0.16f;
        float right = getWidth() * 0.84f;
        canvas.drawLine(left, cy - ih / 2f, right, cy - ih / 2f, guide);
        canvas.drawLine(left, cy + ih / 2f, right, cy + ih / 2f, guide);

        for (int i = 0; i < items.length; i++) {
            float dy = (i - offset) * ih;
            if (Math.abs(dy) > getHeight() / 2f + ih) continue;

            float dist = Math.abs(i - offset);           // 0 = 正中
            float alpha = Math.max(0f, 1f - dist / 2.6f);
            boolean center = dist < 0.5f;

            text.setColor(center ? Ui.TEXT : Ui.DIM);
            text.setAlpha((int) (255 * alpha));
            text.setTextSize(center ? Ui.dp(getContext(), 23) : Ui.dp(getContext(), 16));

            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = cy + dy - (fm.ascent + fm.descent) / 2f;

            canvas.drawText(items[i], cx, baseline, text);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(settle);
        super.onDetachedFromWindow();
    }
}
