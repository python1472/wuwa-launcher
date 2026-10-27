package com.miaojian.wuwalauncher;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 承载还原通知的前台服务。
 *
 * 只做一件事：让那条「还原」通知挂在通知栏上。
 * 用前台服务而不是普通通知，是因为普通通知能被用户划掉，
 * 划掉之后就再没有还原入口了。
 *
 * ===== 关于通知的移除 =====
 *
 * 前台服务的通知**不能**用 NotificationManager.cancel() 移除，
 * 系统会忽略这个调用。必须先 stopForeground(STOP_FOREGROUND_REMOVE)
 * 或停掉服务，通知才会消失。这就是之前「还原后通知还挂着」的原因。
 *
 * 所以这里提供了明确的停止入口：发送 ACTION_STOP 给本服务，
 * 服务自己收尾。外部只管调 stopGuard()。
 *
 * 刻意不做的事：
 *
 * 1. 不在 onTaskRemoved 里还原。
 *    用户划掉启动器只是想清掉界面，不代表他想改分辨率。
 *
 * 2. 不在 onDestroy 里还原。
 *    服务可能因系统回收或前台化失败被销毁，那一刻游戏往往还在跑，
 *    在这里还原会把游戏正在用的分辨率改回去。
 *
 * 真正的还原只有两条路径：主界面的按钮、通知上的按钮。
 */
public class GuardService extends Service {

    /** 请求服务停止并撤下通知 */
    static final String ACTION_STOP = "com.miaojian.wuwalauncher.GUARD_STOP";

    /**
     * 停止守护服务并撤下通知。
     *
     * 用 stopService 而不是发 ACTION_STOP 再 stopSelf：
     * 应用在后台时不允许 startService，但 stopService 任何时候都可用。
     */
    public static void stopGuard(Context c) {
        try {
            c.stopService(new Intent(c, GuardService.class));
        } catch (Throwable ignored) {
        }
        // 双保险：即使服务被别的东西重新拉起，也先把通知撤掉
        RestoreNotification.cancel(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!startForegroundSafely()) {
            stopSelf();
        }
    }

    private boolean startForegroundSafely() {
        try {
            Notification n = RestoreNotification.build(this,
                    "鸣潮以高分辨率运行中",
                    "点「还原」可恢复原生分辨率与导航");
            startForeground(RestoreNotification.id(), n);
            return true;
        } catch (Throwable t) {
            // 前台化失败不能硬撑：系统会在 5 秒内判定超时并杀掉服务
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 显式停止请求
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        // 系统恢复重建时 intent 为 null。若已经没什么可还原的，
        // 就别再挂一条没用的通知
        if (intent == null) {
            new Thread(new Runnable() {
                public void run() {
                    Restorer.Status st = Restorer.inspect(getApplicationContext());
                    if (!st.needsRestore()) {
                        shutdown();
                    }
                }
            }, "wuwa-guard-check").start();
        }
        return START_STICKY;
    }

    /** 撤下通知并结束自己 */
    private void shutdown() {
        try {
            // STOP_FOREGROUND_REMOVE = 1，移除通知
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        RestoreNotification.cancel(this);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // 服务结束时一定要把通知撤掉，否则会留个点不动的按钮
        try {
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        RestoreNotification.cancel(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
