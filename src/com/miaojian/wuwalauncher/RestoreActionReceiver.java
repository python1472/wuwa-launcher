package com.miaojian.wuwalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知里「还原」按钮的接收器。
 *
 * 用户可能正在游戏里，从通知栏直接点还原 —— 不需要回到 App 界面。
 * 还原在后台线程跑，完成后通知自动消失。
 */
public class RestoreActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!RestoreNotification.ACTION_RESTORE.equals(intent.getAction())) return;

        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                Restorer.restoreAll(app, null, true);
            }
        }, "wuwa-restore-from-notification").start();
    }
}
