package com.miaojian.wuwalauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * 显示相关操作：分辨率、密度、导航栏。
 *
 * 全部通过 Shizuku 的 shell 身份执行 —— 普通 App 无权调用 wm / cmd overlay。
 */
public final class DisplayOps {

    public static final int RISK_SAFE = 0;
    public static final int RISK_WARN = 1;
    public static final int RISK_HIGH = 2;
    public static final int RISK_DANGER = 3;

    private static final String OVERLAY_THREE =
            "com.android.internal.systemui.navbar.threebutton";
    private static final String OVERLAY_GESTURE =
            "com.android.internal.systemui.navbar.gestural";

    /** 像素量分档阈值（百万像素），依据实测内存余量设定 */
    private static final double MP_SAFE = 7.5;
    private static final double MP_WARN = 10.0;
    private static final double MP_HIGH = 13.0;

    private DisplayOps() {
    }

    // ---------------- 数据模型 ----------------

    /** 一个可选的渲染档位 */
    public static final class Mode {
        public final String label;   // 例如 "1.4×"
        public final int width;
        public final int height;
        public final int density;
        public final int risk;
        /** 相对屏幕原生的倍率。1.4 表示 1.4×，原生档为 1.0 */
        public final float scale;

        Mode(String label, int width, int height, int density, int risk, float scale) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.density = density;
            this.risk = risk;
            this.scale = scale;
        }

        /**
         * 是否需要弹出风险确认。
         *
         * 阈值定在 1.6×：这个档位及以下像素量在 9M 以内，
         * 实测可用；再往上才需要用户明确知晓后果。
         */
        public boolean needsConfirm() {
            return scale > CONFIRM_THRESHOLD;
        }

        public String sizeText() {
            return width + " × " + height;
        }

        public String sizeArg() {
            return width + "x" + height;
        }

        public double megapixels() {
            return width * (double) height / 1_000_000d;
        }

        public String riskText() {
            switch (risk) {
                case RISK_SAFE:   return "稳妥";
                case RISK_WARN:   return "有风险";
                case RISK_HIGH:   return "风险高";
                default:          return "极易崩溃";
            }
        }
    }

    /** 面板物理信息 */
    public static final class Panel {
        public final int width;
        public final int height;
        public final int density;

        Panel(int w, int h, int d) {
            this.width = w;
            this.height = h;
            this.density = d;
        }

        public String sizeText() {
            return width + " × " + height;
        }
    }

    // ---------------- 读取 ----------------

    /** 读面板真实尺寸与密度。优先用 shell（能绕过 override），失败再退回 Display API。 */
    public static Panel readPanel(Context c, Shell.Log log) {
        int w = 0, h = 0, d = 0;

        if (Shell.ready()) {
            int[] wh = parsePhysicalSize(Shell.exec("wm size", null));
            if (wh != null) {
                w = wh[0];
                h = wh[1];
            }
            d = parsePhysicalDensity(Shell.exec("wm density", null));
        }

        if (w == 0 || h == 0) {
            try {
                DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                Display.Mode mode = display.getMode();
                w = mode.getPhysicalWidth();
                h = mode.getPhysicalHeight();
            } catch (Throwable ignored) {
            }
        }

        if (d == 0) {
            try {
                DisplayMetrics metrics = c.getResources().getDisplayMetrics();
                d = metrics.densityDpi;
            } catch (Throwable ignored) {
            }
        }

        if (w == 0 || h == 0) {
            w = 1080;
            h = 2400;
        }
        if (d == 0) d = 420;

        return new Panel(w, h, d);
    }

    // ---------------- 当前状态检测 ----------------

    /**
     * 读取当前是否被 Override（即分辨率被改过）。
     *
     * wm size 的输出有两种形态：
     *   Physical size: 1280x2800                    ← 原生，没被改过
     *   Physical size: 1280x2800
     *   Override size: 1792x3920                    ← 被改过
     *
     * 这是判断「需不需要还原」最可靠的依据：它是系统本身的真实状态，
     * 不像自己记的标记那样会因清数据而丢失。
     */
    public static boolean hasOverride(Shell.Log log) {
        String out = Shell.exec("wm size", null);
        if (out == null) return false;
        for (String line : out.split("\n")) {
            if (line.trim().startsWith("Override size:")) return true;
        }
        return false;
    }

    /** 读当前密度是否被 Override（wm density 同样有两行形态） */
    public static boolean hasDensityOverride(Shell.Log log) {
        String out = Shell.exec("wm density", null);
        if (out == null) return false;
        for (String line : out.split("\n")) {
            if (line.trim().startsWith("Override density:")) return true;
        }
        return false;
    }

    /** 是否存在任何显示层面的改动痕迹 */
    public static boolean hasAnyOverride(Shell.Log log) {
        return hasOverride(log) || hasDensityOverride(log);
    }

    private static int[] parsePhysicalSize(String out) {
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("Physical size:")) continue;
            String v = s.substring("Physical size:".length()).trim();
            int x = v.indexOf('x');
            if (x <= 0) continue;
            try {
                return new int[]{
                        Integer.parseInt(v.substring(0, x).trim()),
                        Integer.parseInt(v.substring(x + 1).trim())
                };
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static int parsePhysicalDensity(String out) {
        if (out == null) return 0;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("Physical density:")) continue;
            String v = s.substring("Physical density:".length()).trim();
            try {
                return Integer.parseInt(v);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        return 0;
    }

    // ---------------- 档位计算 ----------------

    /**
     * 按屏幕自动计算 1.4× ~ 2.0× 共四档。
     * 尺寸与密度按同一比例缩放，界面元素大小保持不变。
     */
    public static List<Mode> computeTiers(Panel panel) {
        float[] scales = {1.4f, 1.6f, 1.8f, 2.0f};
        List<Mode> list = new ArrayList<>();
        for (float s : scales) {
            list.add(buildMode(panel, s));
        }
        return list;
    }

    /**
     * 按指定倍率构造一个档位。
     * 自定义倍率与预设档位走同一套算法，密度始终按同倍数缩放。
     */
    public static Mode buildMode(Panel panel, float scale) {
        int w = Math.round(panel.width * scale);
        int h = Math.round(panel.height * scale);
        int d = Math.round(panel.density * scale);
        double mp = w * (double) h / 1_000_000d;

        int risk;
        if (mp <= MP_SAFE) risk = RISK_SAFE;
        else if (mp <= MP_WARN) risk = RISK_WARN;
        else if (mp <= MP_HIGH) risk = RISK_HIGH;
        else risk = RISK_DANGER;

        return new Mode(String.format("%.1f×", scale), w, h, d, risk, scale);
    }

    /** 超过这个倍率才弹风险确认框 */
    public static final float CONFIRM_THRESHOLD = 1.6f;

    /** 自定义倍率的上下限（0.1 一档） */
    public static final float CUSTOM_MIN = 1.0f;
    public static final float CUSTOM_MAX = 3.0f;
    public static final int CUSTOM_STEPS = 21;   // 1.0 ~ 3.0，共 21 档

    // ---------------- 应用 ----------------

    /** 应用指定分辨率与密度 */
    public static boolean applyMode(Shell.Log log, int width, int height, int density) {
        boolean ok = Shell.ok("wm size " + width + "x" + height, log);
        ok &= Shell.ok("wm density " + density, log);
        return ok;
    }

    /** 恢复原生分辨率与密度 */
    public static boolean resetMode(Shell.Log log) {
        boolean ok = Shell.ok("wm size reset", log);
        ok &= Shell.ok("wm density reset", log);
        return ok;
    }

    /**
     * 高分辨率下全面屏手势区域会失效，切三键导航。
     * 用 overlay 而不是只写 navigation_mode，两者要配套。
     */
    public static boolean switchToThreeButton(Shell.Log log) {
        boolean a = Shell.ok("cmd overlay enable-exclusive --category " + OVERLAY_THREE, log);
        boolean b = Shell.ok("settings put secure navigation_mode 0", log);
        return a && b;
    }

    public static boolean restoreNavigation(Shell.Log log, int navMode) {
        String overlay = navMode == 0 ? OVERLAY_THREE : OVERLAY_GESTURE;
        boolean a = Shell.ok("cmd overlay enable-exclusive --category " + overlay, log);
        boolean b = Shell.ok("settings put secure navigation_mode " + navMode, log);
        return a && b;
    }


    // ---------------- 方向（Settings.System，不需要 shell） ----------------

    /**
     * 锁定方向。这是 rotation 那套做法：只写 Settings.System，
     * 不动 wm fixed-to-user-rotation（那个是显示级粘性设置，会把游戏卡死）。
     */
    /** 开启系统自动旋转。悬浮窗请求 SENSOR 时必须先打开这个，否则方向被钉死 */
    public static boolean enableAutoRotate(Context c) {
        return putRotationSetting(c, KEY_ACCEL, 1);
    }

    public static boolean lockRotation(Context c, int rotation) {
        try {
            boolean a = android.provider.Settings.System.putInt(
                    c.getContentResolver(),
                    android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
            boolean b = android.provider.Settings.System.putInt(
                    c.getContentResolver(),
                    android.provider.Settings.System.USER_ROTATION, rotation);
            return a && b;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int readRotationSetting(Context c, String key, int def) {
        try {
            return android.provider.Settings.System.getInt(c.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static final String KEY_ACCEL = android.provider.Settings.System.ACCELEROMETER_ROTATION;
    public static final String KEY_USER = android.provider.Settings.System.USER_ROTATION;

    /** 给设置写值（还原时用） */
    public static boolean putRotationSetting(Context c, String key, int value) {
        try {
            return android.provider.Settings.System.putInt(c.getContentResolver(), key, value);
        } catch (Throwable t) {
            return false;
        }
    }
}
