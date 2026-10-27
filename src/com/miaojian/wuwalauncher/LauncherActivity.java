package com.miaojian.wuwalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * 主界面 + 流程编排。
 *
 * 具体操作都委托出去：
 *   显示参数   → DisplayOps
 *   方向悬浮窗 → RotationOverlay
 *   档位测试   → TierTester
 *   还原       → Restorer
 *   视图构建   → Ui
 *
 * 本类只负责「什么时候调用谁」。
 */
public class LauncherActivity extends Activity implements Shell.Log {

    private static final String GAME = "com.kurogame.mingchao";
    private static final int REQ_SHIZUKU = 4213;
    private static final int REQ_NOTIFICATION = 4214;

    private static final int KIND_NONE = 0;
    private static final int KIND_NATIVE = 1;
    private static final int KIND_HD = 2;
    private static final int KIND_TIER = 3;
    private static final int KIND_CUSTOM = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private DisplayOps.Panel panel;
    private List<DisplayOps.Mode> tiers = new ArrayList<>();
    private TierTester tester;

    private int selectedKind = KIND_NONE;
    private DisplayOps.Mode selectedMode;

    private boolean shizukuOk;
    private boolean busy;
    /** 上次拉起游戏的时间戳，用于挡住连点导致的重复启动 */
    private long lastLaunchAt;

    /** 「正在启动」提示框。自动跳转到游戏后要关掉 */
    private AlertDialog launchingDialog;
    /** 「修改系统设置」权限只引导一次，避免每次打开都弹 */
    private static boolean askedWriteSettings;

    // 视图
    private TextView statusDot;
    private TextView statusText;
    private LinearLayout optionNative;
    private LinearLayout optionHd;
    private LinearLayout optionTier;
    private LinearLayout tiersBox;
    private LinearLayout launchBtn;
    private TextView launchLabel;
    private TextView launchHint;
    private TextView logView;
    private TextView restoreBtn;
    private ScrollView logScroll;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int code, int grantResult) {
                    if (code != REQ_SHIZUKU) return;
                    shizukuOk = grantResult == PackageManager.PERMISSION_GRANTED;
                    refreshStatus();
                    line(shizukuOk
                            ? "[Shizuku] 授权成功，分辨率与导航可用"
                            : "[Shizuku] 未授权，仅方向控制可用");
                    if (shizukuOk) {
                        loadPanel();
                        enableLiveNotification();
                        checkStaleState();
                    }
                }
            };

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle state) {
        // 必须用无标题栏主题。
        // 默认主题带 ActionBar，界面上方会多出一条「鸣潮启动器」标题栏；
        // 平时被自绘内容盖住看不出来，但分辨率变化导致重绘时会露出来，
        // 表现为「还原之后 UI 多了一条标题」。
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        super.onCreate(state);

        buildUi();
        tester = new TierTester(this, this);

        // 屏幕参数是只读的，不需要任何权限，开机就读。
        // 高清档要显示「1280 × 2800」这些数字，早读到早显示。
        loadPanel();

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                public void onBinderReceived() {
                    refreshStatus();
                }
            });
        } catch (Throwable t) {
            line("Shizuku 初始化异常：" + t);
        }

        // 只在「确实有脏状态」时补还原。
        // 关键判断：鸣潮还在运行就说明是正常会话，动它会打断游戏。
        ui.postDelayed(new Runnable() {
            public void run() {
                checkStaleState();
            }
        }, 500);

        // 通知权限：Android 13+ 是运行时权限，不申请的话还原通知发不出来
        ensureNotificationPermission();

        // 只申请授权，不改任何设置
        ui.postDelayed(new Runnable() {
            public void run() {
                requestShizuku();
                if (!Permissions.hasWriteSettings(LauncherActivity.this)) {
                    line("提示：缺少「修改系统设置」权限，横竖屏切换可能不生效");
                    if (!askedWriteSettings) {
                        askedWriteSettings = true;
                        askWriteSettingsPermission();
                    }
                }
            }
        }, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 测试中途按 Home 出去、又从通知栏点了还原：
        // 屏幕已经回原生，测试遮罩却还在 Activity 上倒数。
        // 这里校验一次，把无效的测试直接结束掉。
        if (tester != null && tester.invalidateIfScreenReset()) {
            busy = false;
            line("屏幕已被外部还原，测试已自动结束");
            setLaunchEnabled(selectedKind != KIND_NONE);
        }

        // 用户可能在通知栏点过还原，或在外面临时改过设置，
        // 回到界面时按系统真实状态刷新按钮
        if (Shell.ready()) refreshRestoreButton();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        // 提示框不关会泄漏窗口
        dismissLaunchingDialog();
        // 测试中途被销毁：撤掉遮罩并把屏幕还原，避免卡在高分辨率
        if (tester != null) tester.abort();
        super.onDestroy();
    }

    // ==================== 界面 ====================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 24),
                Ui.dp(this, 20), Ui.dp(this, 16));

        // 标题 + 状态点
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.text(this, "鸣潮启动器", Ui.TEXT, 20f, true),
                new LinearLayout.LayoutParams(0, -2, 1f));

        statusDot = Ui.text(this, "●", Ui.AMBER, 11f, false);
        LinearLayout.LayoutParams dotp = new LinearLayout.LayoutParams(-2, -2);
        dotp.rightMargin = Ui.dp(this, 14);
        titleRow.addView(statusDot, dotp);

        // 右上角设置入口
        TextView settingsBtn = Ui.text(this, "设置", Ui.FAINT, 13f, false);
        settingsBtn.setPadding(Ui.dp(this, 6), Ui.dp(this, 4),
                Ui.dp(this, 6), Ui.dp(this, 4));
        titleRow.addView(settingsBtn);
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 面板里能重启桌面进程，测试中来一下会直接打断流程
                if (busy) {
                    line("正在处理，请稍候…");
                    return;
                }
                SettingsPanel.show(LauncherActivity.this, LauncherActivity.this, panel,
                        new Runnable() {
                            public void run() {
                                line("桌面已重启，请打开任意应用确认动画是否恢复");
                            }
                        });
            }
        });

        root.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        // 状态卡
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setBackground(Ui.bg(this, Ui.CARD, Ui.LINE, 11));
        statusCard.setPadding(Ui.dp(this, 13), Ui.dp(this, 10),
                Ui.dp(this, 13), Ui.dp(this, 10));
        statusText = Ui.text(this, "Shizuku：检测中…", Ui.AMBER, 12f, false);
        statusCard.addView(statusText);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(-1, -2);
        scp.topMargin = Ui.dp(this, 14);
        root.addView(statusCard, scp);

        // 分区标题
        TextView section = Ui.text(this, "屏幕分辨率", Ui.FAINT, 12f, false);
        LinearLayout.LayoutParams secp = new LinearLayout.LayoutParams(-1, -2);
        secp.topMargin = Ui.dp(this, 18);
        secp.bottomMargin = Ui.dp(this, 10);
        root.addView(section, secp);

        // 三个选项
        optionNative = buildOption("原生", "1080p · 游戏默认，不改尺寸");
        optionHd = buildOption("高清", "屏幕分辨率 · " + panelText());
        optionTier = buildOption("最大渲染极限", "按屏幕自动计算");
        root.addView(optionNative);
        root.addView(optionHd);
        root.addView(optionTier);

        optionNative.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                select(KIND_NATIVE, null);
            }
        });
        optionHd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 不需要 Shizuku：屏幕本来就跑在这个分辨率。
                // 只有在「当前分辨率被别人改过」时才需要用 Shizuku 清掉覆盖值。
                select(KIND_HD, null);
            }
        });
        optionTier.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleTiers();
            }
        });

        // 档位展开区
        tiersBox = new LinearLayout(this);
        tiersBox.setOrientation(LinearLayout.VERTICAL);
        tiersBox.setBackground(Ui.bg(this, 0xFF13131A, 0xFF26262E, 12));
        tiersBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 12),
                Ui.dp(this, 12), Ui.dp(this, 12));
        tiersBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(-1, -2);
        tbp.topMargin = Ui.dp(this, 9);
        tbp.bottomMargin = Ui.dp(this, 5);
        root.addView(tiersBox, tbp);

        // 启动按钮
        launchBtn = new LinearLayout(this);
        launchBtn.setOrientation(LinearLayout.VERTICAL);
        launchBtn.setGravity(Gravity.CENTER);
        launchBtn.setPadding(Ui.dp(this, 18), Ui.dp(this, 18),
                Ui.dp(this, 18), Ui.dp(this, 18));
        launchLabel = Ui.text(this, "启动游戏", Ui.DISABLED_TEXT, 16.5f, true);
        launchLabel.setGravity(Gravity.CENTER);
        launchBtn.addView(launchLabel);
        launchHint = Ui.text(this, "请先选择分辨率", Ui.DISABLED_TEXT, 11f, false);
        launchHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lhp = new LinearLayout.LayoutParams(-1, -2);
        lhp.topMargin = Ui.dp(this, 5);
        launchBtn.addView(launchHint, lhp);
        launchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onLaunchClicked();
            }
        });
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-1, -2);
        lbp.topMargin = Ui.dp(this, 14);
        lbp.bottomMargin = Ui.dp(this, 14);
        root.addView(launchBtn, lbp);
        setLaunchEnabled(false);

        // 日志
        logScroll = new ScrollView(this);
        logScroll.setBackground(Ui.bg(this, 0xFF101015, 0xFF1E1E26, 11));
        logScroll.setPadding(Ui.dp(this, 11), Ui.dp(this, 11),
                Ui.dp(this, 11), Ui.dp(this, 11));
        logView = Ui.mono(this, "", Ui.LOG_TEXT, 10f);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 还原按钮：默认灰色不可点，只有检测到确实有改动才亮起
        restoreBtn = Ui.button(this, "还原显示", Ui.DISABLED_TEXT,
                Ui.CARD, Ui.LINE, 12, 0, 12);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(this, 12);
        root.addView(restoreBtn, rp);
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doRestore();
            }
        });
        applyRestoreButtonState(false);

        setContentView(root);
    }

    private String panelText() {
        return panel == null ? "" : panel.sizeText();
    }

    private LinearLayout buildOption(String label, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.bg(this, Ui.CARD, Ui.LINE, 13));
        box.setPadding(Ui.dp(this, 15), Ui.dp(this, 13),
                Ui.dp(this, 15), Ui.dp(this, 13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 9);
        box.setLayoutParams(lp);

        TextView t1 = Ui.text(this, label, Ui.TEXT_SUB, 14.5f, true);
        TextView t2 = Ui.text(this, desc, Ui.FAINT, 11.5f, false);
        box.addView(Ui.twoLines(this, t1, t2, 3),
                new LinearLayout.LayoutParams(0, -2, 1f));

        TextView right = Ui.text(this, "", Ui.FAINT, 11f, false);
        box.addView(right);

        box.setTag(new TextView[]{t1, t2, right});
        return box;
    }

    private void paintOption(LinearLayout box, boolean on, String rightText) {
        TextView[] tags = (TextView[]) box.getTag();
        box.setBackground(Ui.bg(this, on ? Ui.CARD_ON : Ui.CARD,
                on ? Ui.ACCENT : Ui.LINE, 13));
        tags[0].setTextColor(on ? Ui.TEXT : Ui.TEXT_SUB);
        tags[2].setText(rightText == null ? "" : rightText);
        tags[2].setTextColor(on ? Ui.ACCENT : Ui.FAINT);
    }

    // ==================== 档位 ====================

    private void toggleTiers() {
        if (busy) {
            line("正在处理，请稍候…");
            return;
        }
        if (tiersBox.getVisibility() == View.VISIBLE) {
            tiersBox.setVisibility(View.GONE);
            return;
        }

        if (shizukuOk && panel == null) {
            line("正在读取屏幕参数…");
            new Thread(new Runnable() {
                public void run() {
                    panel = DisplayOps.readPanel(LauncherActivity.this, null);
                    tiers = DisplayOps.computeTiers(panel);
                    ui.post(new Runnable() {
                        public void run() {
                            TextView desc = ((TextView[]) optionHd.getTag())[1];
                            desc.setText("屏幕分辨率 · " + panel.sizeText());
                            rebuildTiers();
                            tiersBox.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }, "wuwa-panel").start();
            return;
        }

        if (tiers.isEmpty()) {
            line("需要 Shizuku 授权才能读取屏幕参数");
            askShizuku();
            return;
        }
        rebuildTiers();
        tiersBox.setVisibility(View.VISIBLE);
    }

    private void rebuildTiers() {
        tiersBox.removeAllViews();

        TextView tip = Ui.text(this,
                "点击任一档位试跑 " + TierTester.SECONDS + " 秒，检测该分辨率下触摸是否正常",
                Ui.FAINT, 10.5f, false);
        LinearLayout.LayoutParams tipp = new LinearLayout.LayoutParams(-1, -2);
        tipp.bottomMargin = Ui.dp(this, 8);
        tiersBox.addView(tip, tipp);

        for (int i = 0; i < tiers.size(); i++) {
            final int index = i;
            final DisplayOps.Mode mode = tiers.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.bg(this, Ui.CARD, Ui.LINE, 10));
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                    Ui.dp(this, 12), Ui.dp(this, 10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = Ui.dp(this, 8);
            row.setLayoutParams(rp);

            // 测过并通过的档位要能一眼看出来，否则测完列表里没有任何反馈
            boolean passed = selectedMode != null
                    && (selectedKind == KIND_TIER || selectedKind == KIND_CUSTOM)
                    && selectedMode.label.equals(mode.label);

            TextView t1 = Ui.text(this,
                    passed ? mode.label + "  ✓" : mode.label,
                    passed ? Ui.GREEN : Ui.TEXT_SUB, 13f, true);
            TextView t2 = Ui.text(this,
                    mode.sizeText() + " · " + mode.density + "dpi",
                    Ui.FAINT, 10.5f, false);
            row.addView(Ui.twoLines(this, t1, t2, 2),
                    new LinearLayout.LayoutParams(0, -2, 1f));

            if (passed) {
                row.setBackground(Ui.bg(this, 0x1A4ADE80, 0x4D4ADE80, 10));
                row.addView(Ui.chip(this, "已选", riskColor(mode.risk),
                        riskBg(mode.risk)));
            } else {
                row.addView(Ui.chip(this, mode.riskText(),
                        riskColor(mode.risk), riskBg(mode.risk)));
            }

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    onTierClicked(index);
                }
            });
            tiersBox.addView(row);
        }

        // 自定义入口放在列表底部
        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setBackground(Ui.bg(this, 0x00000000, 0xFF3A3A46, 10));
        customRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        TextView cl = Ui.text(this, "自定义倍率…", Ui.DIM, 12.5f, false);
        customRow.addView(cl, new LinearLayout.LayoutParams(0, -2, 1f));
        customRow.addView(Ui.text(this, "1.0× ~ 3.0×", Ui.FAINT, 10.5f, false));
        customRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openCustomPicker();
            }
        });
        tiersBox.addView(customRow);
    }

    /** 打开滚轮选择自定义倍率 */
    private void openCustomPicker() {
        if (panel == null) {
            line("还没读到屏幕参数，请先授权 Shizuku");
            askShizuku();
            return;
        }
        CustomTierDialog.show(this, panel, new CustomTierDialog.Callback() {
            public void onPicked(DisplayOps.Mode mode) {
                startCustomTest(mode);
            }
        });
    }

    /** 自定义倍率确认后，走与预设档位完全相同的测试流程 */
    private void startCustomTest(final DisplayOps.Mode mode) {
        if (busy) return;
        if (!shizukuOk) {
            askShizuku();
            return;
        }
        busy = true;
        setLaunchEnabled(false);
        line("自定义倍率：" + mode.label + " " + mode.sizeText()
                + " · " + mode.density + "dpi");

        tester.start(mode, -1, new TierTester.Callback() {
            public void onFinished(DisplayOps.Mode m, int idx, boolean passed) {
                busy = false;
                if (passed) {
                    selectedKind = KIND_CUSTOM;
                    selectedMode = m;
                    refreshSelectionUi();
                    if (!tiers.isEmpty()) rebuildTiers();
                    line("测试通过：" + m.label + " " + m.sizeText());
                }
                setLaunchEnabled(selectedKind != KIND_NONE);
            }
        });
    }

    private int riskColor(int risk) {
        switch (risk) {
            case DisplayOps.RISK_SAFE: return Ui.GREEN;
            case DisplayOps.RISK_DANGER: return Ui.RED;
            default: return Ui.AMBER;
        }
    }

    private int riskBg(int risk) {
        switch (risk) {
            case DisplayOps.RISK_SAFE: return 0x1A4ADE80;
            case DisplayOps.RISK_DANGER: return 0x1AE06A5A;
            default: return 0x1AE8C46A;
        }
    }

    private void onTierClicked(int index) {
        if (busy) return;
        if (!shizukuOk) {
            askShizuku();
            return;
        }

        final DisplayOps.Mode mode = tiers.get(index);

        // 预设档位与自定义走同一套确认规则：
        // ≤1.6× 直接测，>1.6× 先看免责声明
        CustomTierDialog.confirmAndPick(this, panel, mode,
                new CustomTierDialog.Callback() {
                    public void onPicked(DisplayOps.Mode m) {
                        beginTierTest(m);
                    }
                });
    }

    /** 真正开始档位测试 */
    private void beginTierTest(final DisplayOps.Mode mode) {
        busy = true;
        setLaunchEnabled(false);

        tester.start(mode, -1, new TierTester.Callback() {
            public void onFinished(DisplayOps.Mode m, int idx, boolean passed) {
                busy = false;
                if (passed) {
                    selectedKind = KIND_TIER;
                    selectedMode = m;
                    refreshSelectionUi();
                    // 重建列表，让刚通过的档位显示 ✓
                    if (!tiers.isEmpty()) rebuildTiers();
                    line("测试通过：" + m.label + " " + m.sizeText());
                }
                setLaunchEnabled(selectedKind != KIND_NONE);
            }
        });
    }

    // ==================== 选择 ====================

    private void select(int kind, DisplayOps.Mode mode) {
        // 忙碌时（挂窗口、改分辨率、测试中）不允许改选择，
        // 否则会出现「界面显示原生、实际按 1.8× 启动」这种不一致
        if (busy) {
            line("正在处理，请稍候…");
            return;
        }
        selectedKind = kind;
        selectedMode = mode;
        refreshSelectionUi();
        setLaunchEnabled(true);
    }

    private void refreshSelectionUi() {
        paintOption(optionNative, selectedKind == KIND_NATIVE,
                selectedKind == KIND_NATIVE ? "已选" : null);
        paintOption(optionHd, selectedKind == KIND_HD,
                selectedKind == KIND_HD ? "已选" : null);
        boolean tierSelected = selectedKind == KIND_TIER || selectedKind == KIND_CUSTOM;
        paintOption(optionTier, tierSelected,
                tierSelected && selectedMode != null ? selectedMode.label : null);
    }

    private void setLaunchEnabled(boolean enabled) {
        if (enabled && !busy) {
            launchBtn.setBackground(Ui.bg(this, Ui.ACCENT, 0, 16));
            launchLabel.setTextColor(0xFFFFFFFF);
            launchHint.setText(selectedDescription());
            launchHint.setTextColor(0x66FFFFFF);
        } else {
            launchBtn.setBackground(Ui.bg(this, 0xFF1B1B21, Ui.LINE, 16));
            launchLabel.setTextColor(Ui.DISABLED_TEXT);
            launchHint.setText(busy ? "处理中…" : "请先选择分辨率");
            launchHint.setTextColor(Ui.DISABLED_TEXT);
        }
    }

    private String selectedDescription() {
        switch (selectedKind) {
            case KIND_NATIVE:
                return "原生 · 不改分辨率";
            case KIND_HD:
                return "高清 · " + panelText();
            case KIND_TIER:
            case KIND_CUSTOM:
                return selectedMode == null ? ""
                        : selectedMode.label + " · " + selectedMode.sizeText();
            default:
                return "";
        }
    }

    // ==================== 启动 ====================

    private void onLaunchClicked() {
        if (busy || selectedKind == KIND_NONE) return;

        // 刚拉起过游戏就挡一下。原生档是同步完成的，busy 立刻归位，
        // 快速连点会重复 startActivity，游戏可能因此重进一次。
        if (android.os.SystemClock.elapsedRealtime() - lastLaunchAt < 1500) {
            return;
        }

        // 原生档：完全交给游戏自己。
        // 不挂窗口、不动旋转、不碰分辨率 —— 游戏说多少就是多少。
        if (selectedKind == KIND_NATIVE) {
            busy = true;
            setLaunchEnabled(false);
            line("原生档：不干预，游戏按自己的分辨率运行");
            launchGame();
            return;
        }

        // 以下是「高清」及以上档位，共同点是都要矫正游戏窗口的方向。
        //
        // 原因：游戏内部分辨率被它自己声明的横屏限制住了，
        // 系统按横屏给它一个交换过宽高的窗口，渲染尺寸就上不去。
        // 挂一个透明窗口把方向压到「跟随重力」，窗口尺寸变成屏幕的真实宽高，
        // 游戏才会按屏幕分辨率渲染。
        //
        // 这一步只需要悬浮窗权限，不需要 Shizuku。
        if (!Permissions.hasOverlay(this)) {
            askOverlayPermission();
            return;
        }

        // 高清档不改尺寸，所以也不要求 Shizuku。
        // 唯一的例外是屏幕当前留着别人设的覆盖值，那要清掉才算「回到物理分辨率」，
        // 清覆盖需要 shell 身份。
        boolean needResize = selectedKind == KIND_TIER
                || selectedKind == KIND_CUSTOM
                || (selectedKind == KIND_HD && DisplayOps.hasAnyOverride(null));

        if (needResize && !shizukuOk) {
            askShizuku();
            return;
        }

        busy = true;
        setLaunchEnabled(false);
        showLaunchingDialog();

        // 步骤 1：先挂方向悬浮窗，失败重试 3 次
        RotationOverlay.show(this, RotationOverlay.FOLLOW_GRAVITY,
                new RotationOverlay.Callback() {
                    public void onResult(boolean success, String message) {
                        if (!success) {
                            busy = false;
                            setLaunchEnabled(true);
                            dismissLaunchingDialog();
                            line("方向控制失败：" + message);
                            showRotationFailedDialog(message);
                            return;
                        }
                        line("方向控制已就绪（跟随重力）");
                        applyAndLaunch();
                    }
                });
    }

    private void askOverlayPermission() {
        new AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("方向控制依赖一个透明悬浮窗。\n\n"
                        + "请在接下来的页面里允许「显示在其他应用上层」，返回后再次点启动。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Permissions.openOverlaySettings(LauncherActivity.this);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askWriteSettingsPermission() {
        new AlertDialog.Builder(this)
                .setTitle("需要「修改系统设置」权限")
                .setMessage("游戏内横竖屏跟随需要打开系统自动旋转，"
                        + "这一步要写系统设置。\n\n"
                        + "不授权也能启动游戏，但画面方向可能被游戏自身的横屏声明固定住。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Permissions.openWriteSettings(LauncherActivity.this);
                    }
                })
                .setNegativeButton("跳过", null)
                .show();
    }

    private void showRotationFailedDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("屏幕方向控制失败")
                .setMessage(message + "\n\n游戏未启动，屏幕未做任何改动。")
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        onLaunchClicked();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 步骤 2：改显示参数 → 挂守护服务 → 拉起游戏 */
    private void applyAndLaunch() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    applyAndLaunchInner();
                } catch (Throwable t) {
                    line("应用设置时出错：" + t);
                    ui.post(new Runnable() {
                        public void run() {
                            dismissLaunchingDialog();
                            busy = false;
                            setLaunchEnabled(selectedKind != KIND_NONE);
                        }
                    });
                }
            }
        }, "wuwa-apply").start();
    }

    private void applyAndLaunchInner() {
        // 记录快照。两个条件缺一不可：
        //   1. 还没有快照（已有就沿用，避免把改过的状态当成原始值）
        //   2. 当前屏幕是干净的（没有 Override），否则记下的分辨率是被改过的
        boolean clean = !DisplayOps.hasAnyOverride(LauncherActivity.this);
        if (!Prefs.hasSnapshot(LauncherActivity.this) || clean) {
            int nav = readNavMode();
            int accel = DisplayOps.readRotationSetting(
                    LauncherActivity.this, DisplayOps.KEY_ACCEL, 1);
            int user = DisplayOps.readRotationSetting(
                    LauncherActivity.this, DisplayOps.KEY_USER, 0);

            if (clean) {
                Prefs.snapshot(LauncherActivity.this, nav, accel, user, true);
                line("已记录当前显示设置（干净状态），可完整还原");
            } else if (!Prefs.hasSnapshot(LauncherActivity.this)) {
                // 屏幕已被改过又没有任何记录：只能以当前值为基准，
                // 至少保证「还原」能把屏幕恢复到你打开 App 时的样子
                Prefs.snapshot(LauncherActivity.this, nav, accel, user, false);
                line("屏幕已有未还原的改动，以当前状态为基准记录");
            }
        } else {
            line("沿用已有的原始记录");
        }

        boolean ok = true;

        if (shizukuOk) {
            if (selectedKind == KIND_HD) {
                // 高清档一般走到这里是「有残留覆盖值要清」。
                // 没有覆盖值时不需要动 shell，这里只是保险。
                if (DisplayOps.hasAnyOverride(null)) {
                    ok = DisplayOps.resetMode((Shell.Log) LauncherActivity.this);
                }
            } else if ((selectedKind == KIND_TIER || selectedKind == KIND_CUSTOM)
                    && selectedMode != null) {
                ok = DisplayOps.applyMode((Shell.Log) LauncherActivity.this,
                        selectedMode.width, selectedMode.height,
                        selectedMode.density);
            }

            // 改了分辨率才需要切三键导航：高倍率下全面屏手势区会失效
            if (selectedKind == KIND_TIER || selectedKind == KIND_CUSTOM) {
                ok &= DisplayOps.switchToThreeButton(
                        (Shell.Log) LauncherActivity.this);
            }
        } else {
            line("无 Shizuku：跳过分辨率与导航，方向矫正与自动旋转照常");
        }

        // 悬浮窗用的是「跟随重力」，必须同时打开系统自动旋转，
        // 否则 user_rotation 会给钉死，方向请求被忽略
        boolean rotOk = DisplayOps.enableAutoRotate(LauncherActivity.this);
        line(rotOk ? "已开启自动旋转（配合方向悬浮窗）"
                : "无法开启自动旋转：缺少「修改系统设置」权限");

        startGuard();

        final boolean result = ok;
        ui.post(new Runnable() {
            public void run() {
                line(result ? "-- 全部设置已生效" : "-- 部分设置未生效，见日志");
                launchGame();
            }
        });
    }

    private void startGuard() {
        try {
            startForegroundService(new Intent(this, GuardService.class));
        } catch (Throwable t) {
            try {
                startService(new Intent(this, GuardService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    private int readNavMode() {
        try {
            return Settings.Secure.getInt(getContentResolver(), "navigation_mode", 2);
        } catch (Throwable t) {
            return 2;
        }
    }

    /**
     * 显示「正在启动」提示。
     *
     * 挂窗口 + 改分辨率最慢要两三秒，这段时间界面只有按钮变灰，
     * 看不出在干什么。给个明确的提示，也顺便告诉用户别急着退出 ——
     * 后台线程不会因为界面被关掉就中断，照样会把游戏拉起来。
     */
    private void showLaunchingDialog() {
        dismissLaunchingDialog();
        try {
            launchingDialog = new AlertDialog.Builder(this)
                    .setTitle("正在启动")
                    .setMessage("正在准备显示设置并拉起鸣潮…\n\n请勿退出，否则可能停在中间状态。")
                    .setCancelable(false)
                    .create();
            launchingDialog.show();
        } catch (Throwable t) {
            launchingDialog = null;
        }
    }

    /** 关掉「正在启动」提示。启动成功或失败都要调 */
    private void dismissLaunchingDialog() {
        if (launchingDialog == null) return;
        try {
            if (launchingDialog.isShowing()) launchingDialog.dismiss();
        } catch (Throwable ignored) {
        }
        launchingDialog = null;
    }

    private void launchGame() {
        // 无论是成功跳转还是找不到游戏，都先把提示关掉
        dismissLaunchingDialog();

        lastLaunchAt = android.os.SystemClock.elapsedRealtime();
        Intent i = getPackageManager().getLaunchIntentForPackage(GAME);
        if (i == null) {
            line("找不到鸣潮，请确认已安装");
            busy = false;
            setLaunchEnabled(true);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        line(">> 已拉起鸣潮");
        busy = false;
        setLaunchEnabled(selectedKind != KIND_NONE);
    }

    // ==================== 还原 ====================

    private void doRestore() {
        if (busy) return;
        busy = true;
        setLaunchEnabled(false);
        // 还原期间按钮灰掉，防止连点导致两条还原命令交叉执行
        ui.post(new Runnable() {
            public void run() {
                applyRestoreButtonState(false);
                restoreBtn.setText("正在还原…");
            }
        });
        line("开始还原…");

        new Thread(new Runnable() {
            public void run() {
                Restorer.restoreAll(LauncherActivity.this, LauncherActivity.this, true);
                // 通知的撤下由 restoreAll 内部通过 stopGuard 完成
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        restoreBtn.setText("还原显示");
                        refreshRestoreButton();
                        setLaunchEnabled(selectedKind != KIND_NONE);
                    }
                });
            }
        }, "wuwa-restore").start();
    }

    // ==================== Shizuku ====================

    /**
     * 检查有没有「改了但没还原」的残留状态。
     *
     * 判断依据是系统真实状态（Override），不是自己记的标记 ——
     * 这样即使 App 数据被清空、或上次被强行杀掉没来得及记录，
     * 也能正确识别出屏幕还停在超高分辨率。
     *
     * 只更新界面与通知，**不自动还原**：游戏可能正跑着，
     * 用户自己决定什么时候点还原。
     */
    private void checkStaleState() {
        if (!Shell.ready()) return;

        new Thread(new Runnable() {
            public void run() {
                final Restorer.Status st = Restorer.inspect(LauncherActivity.this);
                final String pid = Shell.exec("pidof " + GAME, null);
                final boolean gameRunning = pid != null && pid.trim().length() > 0;

                ui.post(new Runnable() {
                    public void run() {
                        refreshRestoreButton();

                        if (!st.needsRestore()) {
                            GuardService.stopGuard(LauncherActivity.this);
                            return;
                        }

                        if (gameRunning) {
                            line("检测到 " + st.describe() + "（鸣潮运行中）");
                        } else {
                            line("检测到 " + st.describe() + "（鸣潮未运行）");
                        }
                        // 有东西可还原就把通知挂上，用户随时能点
                        startGuard();
                    }
                });
            }
        }, "wuwa-stale-check").start();
    }

    /**
     * 开启实况通知（状态栏胶囊 / ColorOS 流体云）。
     *
     * 这个权限的 appop 默认是 ignore，不打开的话还原通知只能是普通常驻形式。
     * 系统设置里入口位置各机型不同，用 Shizuku 直接开更省事。
     */
    private void enableLiveNotification() {
        new Thread(new Runnable() {
            public void run() {
                if (RestoreNotification.isPromotionAllowed(LauncherActivity.this)) {
                    return;
                }
                final boolean ok = RestoreNotification.enablePromotionByShell(
                        LauncherActivity.this);
                ui.post(new Runnable() {
                    public void run() {
                        if (ok) {
                            line("已开启实况通知权限");
                        } else {
                            line("未能开启实况通知权限，还原入口只在通知栏");
                        }
                        logLiveNotificationDiagnostics();
                    }
                });
            }
        }, "wuwa-live-notify").start();
    }

    /**
     * 打印实况通知的诊断信息。
     *
     * 判断能不能显示成胶囊取决于三个条件，缺哪一项都能从日志看出来：
     *   权限被允许 / 系统同意发送 / 通知本身具备可提升特征
     */
    private void logLiveNotificationDiagnostics() {
        new Thread(new Runnable() {
            public void run() {
                final boolean allowed = RestoreNotification.isPromotionAllowed(
                        LauncherActivity.this);
                final boolean canPost = RestoreNotification.canPostPromoted(
                        LauncherActivity.this);
                final boolean promotable = RestoreNotification.hasPromotableCharacteristics(
                        LauncherActivity.this, "鸣潮以高分辨率运行中",
                        "点「还原」可恢复原生分辨率与导航");

                ui.post(new Runnable() {
                    public void run() {
                        line("实况通知：权限=" + (allowed ? "允许" : "未允许")
                                + " 系统同意=" + (canPost ? "是" : "否")
                                + " 可提升=" + (promotable ? "是" : "否"));
                        if (allowed && canPost && promotable) {
                            line("条件齐全，通知应显示为状态栏胶囊");
                        } else if (!allowed) {
                            line("缺权限：POST_PROMOTED_NOTIFICATIONS 未允许");
                        } else if (!promotable) {
                            line("通知缺少可提升特征（需要 ProgressStyle + 进度条）");
                        } else {
                            line("系统拒绝了提升请求，可能该机型不支持或需要额外开关");
                        }
                    }
                });
            }
        }, "wuwa-live-diag").start();
    }

    /** 按系统真实状态刷新「还原显示」按钮的可用性 */
    private void refreshRestoreButton() {
        if (restoreBtn == null) return;
        new Thread(new Runnable() {
            public void run() {
                final boolean needed = Shell.ready()
                        && Restorer.inspect(LauncherActivity.this).needsRestore();
                ui.post(new Runnable() {
                    public void run() {
                        applyRestoreButtonState(needed);
                    }
                });
            }
        }, "wuwa-refresh-restore").start();
    }

    private void applyRestoreButtonState(boolean enabled) {
        if (restoreBtn == null) return;
        if (enabled) {
            restoreBtn.setTextColor(0xFFF0B8AC);
            restoreBtn.setBackground(Ui.bg(this, 0x29C24A34, 0x80C24A34, 12));
        } else {
            restoreBtn.setTextColor(Ui.DISABLED_TEXT);
            restoreBtn.setBackground(Ui.bg(this, Ui.CARD, Ui.LINE, 12));
        }
        restoreBtn.setEnabled(enabled);
    }

    /** 申请通知权限。缺了它，还原通知就发不出来 */
    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFICATION);
        } catch (Throwable t) {
            line("通知权限申请失败：" + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIFICATION) {
            boolean granted = results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                line("未授予通知权限：还原通知将无法显示，只能从界面还原");
            }
        }
    }

    private void requestShizuku() {
        try {
            if (!Shell.available()) {
                shizukuOk = false;
                line("[Shizuku] 服务未运行 —— 仅方向控制可用");
                refreshStatus();
                return;
            }
            if (Shell.granted()) {
                shizukuOk = true;
                line("[Shizuku] 已授权 v" + Shizuku.getVersion());
                refreshStatus();
                loadPanel();
                enableLiveNotification();
                checkStaleState();
            } else {
                line("[Shizuku] 请求授权，请点「允许」…");
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable t) {
            shizukuOk = false;
            line("[Shizuku] 申请失败：" + t);
            refreshStatus();
        }
    }

    private void askShizuku() {
        new AlertDialog.Builder(this)
                .setTitle("需要 Shizuku 授权")
                .setMessage("修改分辨率与切换导航栏需要 Shizuku 的 shell 权限。\n\n"
                        + "未授权时仍可使用方向控制，但无法测试渲染档位。")
                .setPositiveButton("去申请", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        requestShizuku();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadPanel() {
        new Thread(new Runnable() {
            public void run() {
                panel = DisplayOps.readPanel(LauncherActivity.this, null);
                tiers = DisplayOps.computeTiers(panel);
                ui.post(new Runnable() {
                    public void run() {
                        TextView desc = ((TextView[]) optionHd.getTag())[1];
                        desc.setText("屏幕分辨率 · " + panel.sizeText());
                    }
                });
            }
        }, "wuwa-panel").start();
    }

    private void refreshStatus() {
        ui.post(new Runnable() {
            public void run() {
                if (shizukuOk) {
                    statusDot.setTextColor(Ui.GREEN);
                    statusText.setText("Shizuku 已授权 · 分辨率 / 密度 / 导航可用");
                    statusText.setTextColor(Ui.GREEN);
                } else if (Shell.available()) {
                    statusDot.setTextColor(Ui.AMBER);
                    statusText.setText("Shizuku 已连接但未授权 · 仅方向控制可用");
                    statusText.setTextColor(Ui.AMBER);
                } else {
                    statusDot.setTextColor(Ui.AMBER);
                    statusText.setText("Shizuku 未运行 · 仅方向控制可用");
                    statusText.setTextColor(Ui.AMBER);
                }
            }
        });
    }

    // ==================== 日志 ====================

    @Override
    public void line(final String s) {
        ui.post(new Runnable() {
            public void run() {
                logView.append(s + "\n");
                logScroll.post(new Runnable() {
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }
}
