package com.miaojian.wuwalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 渲染档测试。
 *
 * 点选一个档位后立刻应用该分辨率，给 10 秒让用户确认触摸是否正常，
 * 结束后无论通过还是超时都先还原屏幕。
 *
 * 屏幕上的滑动轨迹只是示意线条 —— 真正的「跟手判断」由用户自己做，
 * 系统不会把触摸坐标回传给应用（没有 shell 权限读不到其它应用的事件）。
 */
public final class TierTester {

    public interface Callback {
        /** 测试结束并已还原屏幕 */
        void onFinished(DisplayOps.Mode mode, int index, boolean passed);
    }

    public static final int SECONDS = 10;

    private final Activity activity;
    private final Shell.Log log;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout mask;
    private boolean finished;
    /** 进入测试前是否已处于「已改动」状态。测试结束后要还原成原样 */
    private boolean wasApplied;

    public TierTester(Activity activity, Shell.Log log) {
        this.activity = activity;
        this.log = log;
    }

    /** 是否有测试正在进行 */
    public boolean isRunning() {
        return mask != null && !finished;
    }

    /**
     * 校验测试是否还有效。
     *
     * 场景：测试中途按 Home 回桌面，再从通知栏点了「还原」——
     * 屏幕已经回到原生，但测试遮罩还挂在 Activity 上倒数。
     * 用户切回来会看到一个毫无意义的测试界面。这时应该直接结束测试。
     *
     * @return true 表示测试已失效并被终止
     */
    public boolean invalidateIfScreenReset() {
        if (!isRunning()) return false;
        // 屏幕已经不是测试时设的那个尺寸，说明外部有人还原过
        if (DisplayOps.hasAnyOverride(null)) return false;
        finished = true;
        removeMask();
        Prefs.setApplied(activity, false);
        return true;
    }

    /** 页面被销毁时兜底：撤掉遮罩并把屏幕恢复回去 */
    public void abort() {
        if (!isRunning()) return;
        finished = true;
        removeMask();
        new Thread(new Runnable() {
            public void run() {
                // 还原成功才处理标记；若进测试前就已经是「已改动」状态
                // （比如游戏正以高分辨率运行），标记必须保留，否则
                // 退出时就没人去还原导航栏了
                if (DisplayOps.resetMode(log) && !wasApplied) {
                    Prefs.setApplied(activity, false);
                }
            }
        }, "wuwa-test-abort").start();
    }

    public void start(final DisplayOps.Mode mode, final int index, final Callback callback) {
        log.line("正在应用 " + mode.label + " …");

        new Thread(new Runnable() {
            public void run() {
                // 先把「已改动」标记打上：万一下一步崩溃，
                // 下次打开 App 时的补还原才能生效
                wasApplied = Prefs.isApplied(activity);
                Prefs.setApplied(activity, true);
                final boolean applied = DisplayOps.applyMode(
                        log, mode.width, mode.height, mode.density);
                ui.post(new Runnable() {
                    public void run() {
                        if (!applied) {
                            log.line("应用失败，请检查 Shizuku 授权");
                            Prefs.setApplied(activity, wasApplied);
                            callback.onFinished(mode, index, false);
                            return;
                        }
                        show(mode, index, callback);
                    }
                });
            }
        }, "wuwa-apply-mode").start();
    }

    private void show(final DisplayOps.Mode mode, final int index, final Callback callback) {
        finished = false;

        mask = new FrameLayout(activity);
        mask.setBackgroundColor(0xE60A0A0E);

        // 触摸轨迹垫在最下层：它只接收没被上层消费的触摸，
        // 所以「确认无误」按钮照常可点。必须先加，后加的盖在上面。
        mask.addView(new TouchTrailView(activity),
                new FrameLayout.LayoutParams(-1, -1));

        LinearLayout center = new LinearLayout(activity);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        mask.addView(center, new FrameLayout.LayoutParams(-1, -1));

        final TextView ring = Ui.text(activity, String.valueOf(SECONDS),
                Ui.TEXT, 34f, false);
        ring.setGravity(Gravity.CENTER);
        ring.setBackground(Ui.bg(activity, 0x00000000, 0x38C24A34, 60));
        LinearLayout.LayoutParams ringp = new LinearLayout.LayoutParams(
                Ui.dp(activity, 112), Ui.dp(activity, 112));
        ringp.gravity = Gravity.CENTER_HORIZONTAL;
        center.addView(ring, ringp);

        TextView title = Ui.text(activity, "正在测试触摸", Ui.TEXT, 14.5f, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(activity, 16);
        center.addView(title, tp);

        TextView size = Ui.text(activity, mode.sizeText() + " · " + mode.label,
                Ui.AMBER, 11.5f, false);
        size.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(activity, 5);
        center.addView(size, sp);

        TextView hint = Ui.text(activity,
                "滑动屏幕试试，轨迹会跟着手指画出来\n" + SECONDS + " 秒后自动恢复原样",
                Ui.FAINT, 11.5f, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(activity, 9);
        center.addView(hint, hp);

        TextView early = Ui.button(activity, "确认无误", 0xFFF0B8AC,
                0x29C24A34, 0x80C24A34, 12, 26, 11);
        early.setTextSize(13f);
        early.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -2);
        ep.gravity = Gravity.CENTER_HORIZONTAL;
        ep.topMargin = Ui.dp(activity, 22);
        center.addView(early, ep);
        early.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmEarly(mode, index, callback);
            }
        });

        final int[] remain = new int[]{SECONDS};
        final Runnable tick = new Runnable() {
            public void run() {
                if (finished) return;
                remain[0]--;
                ring.setText(String.valueOf(Math.max(remain[0], 0)));
                if (remain[0] <= 0) {
                    finish(mode, index, true, callback);
                } else {
                    ui.postDelayed(this, 1000);
                }
            }
        };

        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        root.addView(mask, new FrameLayout.LayoutParams(-1, -1));
        ui.postDelayed(tick, 1000);
    }

    private void confirmEarly(final DisplayOps.Mode mode, final int index,
                              final Callback callback) {
        String msg = "确认后将提前结束测试，并以 "
                + mode.sizeText() + "（" + mode.density + "dpi）作为启动分辨率。";
        if (mode.risk >= DisplayOps.RISK_WARN) {
            msg += "\n\n该档位属于「" + mode.riskText() + "」区间，"
                    + "进入大场景时可能因内存不足闪退。";
        }

        new AlertDialog.Builder(activity)
                .setTitle("确认 " + mode.label + " 触摸正常？")
                .setMessage(msg)
                .setPositiveButton("确认无误", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        finish(mode, index, true, callback);
                    }
                })
                .setNegativeButton("继续测满 " + SECONDS + " 秒",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                // 什么都不做，倒计时继续
                            }
                        })
                .show();
    }

    /** 结束测试：先还原屏幕，再回调 */
    private void finish(final DisplayOps.Mode mode, final int index,
                        final boolean passed, final Callback callback) {
        if (finished) return;
        finished = true;

        removeMask();
        log.line("正在恢复屏幕…");

        new Thread(new Runnable() {
            public void run() {
                // 还原成功才处理标记；进测试前就有改动的话标记要保留
                if (DisplayOps.resetMode(log) && !wasApplied) {
                    Prefs.setApplied(activity, false);
                }
                ui.post(new Runnable() {
                    public void run() {
                        log.line("已恢复原生分辨率");
                        callback.onFinished(mode, index, passed);
                    }
                });
            }
        }, "wuwa-test-restore").start();
    }

    private void removeMask() {
        if (mask != null) {
            ViewGroup parent = (ViewGroup) mask.getParent();
            if (parent != null) parent.removeView(mask);
            mask = null;
        }
    }
}
