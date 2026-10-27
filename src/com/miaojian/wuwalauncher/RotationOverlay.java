package com.miaojian.wuwalauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * 方向控制：1×1 透明悬浮窗。
 *
 * 原理 —— 系统按「最上层窗口请求的方向」决定显示方向。
 * 这个透明窗口盖在游戏之上，把 screenOrientation 写进窗口参数，
 * 游戏自己声明的方向就被压住了。
 *
 * 不需要 root、不需要 Shizuku、不需要无障碍，只要悬浮窗权限。
 */
public final class RotationOverlay {

    public interface Callback {
        void onResult(boolean success, String message);
    }

    /** 跟随重力：交给系统处理，自己不做判断 */
    public static final int FOLLOW_GRAVITY =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR;
    /** 强制竖屏 */
    public static final int LOCK_PORTRAIT =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    /** 强制横屏 */
    public static final int LOCK_LANDSCAPE =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;

    private static final int MAX_RETRY = 3;
    private static final long RETRY_DELAY_MS = 300;

    private static WindowManager windowManager;
    private static View overlayView;
    private static WindowManager.LayoutParams params;

    private RotationOverlay() {
    }

    public static boolean canDraw(Context c) {
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isShown() {
        return overlayView != null;
    }

    /**
     * 挂载悬浮窗。失败自动重试，最多 3 次。
     * 回调一定在主线程执行。
     */
    public static void show(final Context context, final int orientation,
                            final Callback callback) {
        final Context app = context.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());

        if (!canDraw(app)) {
            ui.post(new Runnable() {
                public void run() {
                    callback.onResult(false, "缺少「显示在其他应用上层」权限");
                }
            });
            return;
        }

        attempt(app, orientation, 1, ui, callback);
    }

    private static void attempt(final Context app, final int orientation,
                                final int tryIndex, final Handler ui,
                                final Callback callback) {
        ui.post(new Runnable() {
            public void run() {
                String error = attach(app, orientation);
                if (error == null) {
                    callback.onResult(true, "方向控制已就绪");
                    return;
                }
                if (tryIndex < MAX_RETRY) {
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            attempt(app, orientation, tryIndex + 1, ui, callback);
                        }
                    }, RETRY_DELAY_MS);
                } else {
                    callback.onResult(false, "挂载失败：" + error);
                }
            }
        });
    }

    /** 返回 null 表示成功，否则返回错误描述 */
    private static String attach(Context c, int orientation) {
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            }
            if (windowManager == null) return "拿不到 WindowManager";

            if (overlayView == null) {
                // 注意：不要 setAlpha(0)。
                // View 没有 background 本身即全透明，而 alpha=0 会让窗口
                // 被判定为「不可见」，系统就会忽略它的方向请求 —— 方向控制将完全失效。
                overlayView = new View(c);
                overlayView.setBackgroundColor(0x00000000);

                params = new WindowManager.LayoutParams(
                        1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                params.screenOrientation = orientation;
                windowManager.addView(overlayView, params);
            } else {
                params.screenOrientation = orientation;
                windowManager.updateViewLayout(overlayView, params);
            }
            return null;
        } catch (Throwable t) {
            // 失败时确保残留状态被清掉，避免重试时叠加
            removeQuietly();
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 移除悬浮窗 */
    public static void hide() {
        Handler ui = new Handler(Looper.getMainLooper());
        ui.post(new Runnable() {
            public void run() {
                removeQuietly();
            }
        });
    }

    private static void removeQuietly() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
        }
        overlayView = null;
        params = null;
    }
}
