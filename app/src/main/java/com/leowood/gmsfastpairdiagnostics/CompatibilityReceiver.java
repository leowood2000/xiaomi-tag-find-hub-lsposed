package com.leowood.gmsfastpairdiagnostics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

public final class CompatibilityReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Never trust broadcast extras. Re-read the installed package locally.
        check(context);
    }

    public static void check(Context context) {
        try {
            PackageInfo info = GmsCompatibility.installed(context);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (GmsCompatibility.mapping(info) != null) {
                manager.cancel(98);
                return;
            }
            String key = info.getLongVersionCode() + ":" + info.versionName;
            SharedPreferences prefs = context.getSharedPreferences("compatibility", 0);
            if (prefs.getBoolean(key, false) || !manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new NotificationChannel(
                    "compatibility", "GMS 兼容提醒", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(context, 0,
                    new Intent(context, CompatibilityActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(98, new Notification.Builder(context, "compatibility")
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Find Hub 模块需要适配新版 GMS")
                    .setContentText("检测到未知版本 " + info.versionName)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "当前 GMS：" + info.versionName
                                    + "。配对和云端混淆 Hook 已停用，请更新模块。点击查看兼容状态。"))
                    .setContentIntent(open).setAutoCancel(true).build());
            prefs.edit().putBoolean(key, true).apply();
        } catch (Exception ignored) {
            // Missing GMS or denied notifications must not interfere with apps.
        }
    }
}
