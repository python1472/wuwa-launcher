package com.miaojian.wuwalauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import moe.shizuku.api.BinderContainer;
import rikka.shizuku.Shizuku;

/**
 * 自己实现的 Shizuku 接收端。
 * 原版 rikka.shizuku.ShizukuProvider 在 attachInfo 里会硬性检查 android:exported，
 * 手写 AXML 时该属性读不出来（框架解析差异），会直接抛异常导致进程起不来。
 * 这里做同样的事，但不做那个检查。
 */
public class BinderProvider extends ContentProvider {

    private static final String TAG = "WuwaBinderProvider";
    private static final String EXTRA_BINDER =
            "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        if ("sendBinder".equals(method) && extras != null) {
            try {
                extras.setClassLoader(BinderContainer.class.getClassLoader());
                Bundle container = extras.getBundle("binderContainer");
                BinderContainer bc = null;
                if (container != null) {
                    container.setClassLoader(BinderContainer.class.getClassLoader());
                    bc = container.getParcelable("binder");
                }
                if (bc == null) {
                    bc = extras.getParcelable(EXTRA_BINDER);
                }
                if (bc != null && bc.binder != null) {
                    Log.d(TAG, "收到 Shizuku binder");
                    Shizuku.onBinderReceived(bc.binder, getContext().getPackageName());
                    reply.putBoolean("ok", true);
                } else {
                    Log.w(TAG, "extras 里没有 binder");
                }
            } catch (Throwable t) {
                Log.e(TAG, "处理 binder 失败", t);
            }
        } else if ("getBinder".equals(method)) {
            Log.d(TAG, "getBinder 请求");
        }
        return reply;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
