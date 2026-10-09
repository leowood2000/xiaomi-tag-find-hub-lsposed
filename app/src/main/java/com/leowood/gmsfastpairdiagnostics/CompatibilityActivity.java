package com.leowood.gmsfastpairdiagnostics;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class CompatibilityActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Find Hub · 兼容状态");
    }

    @Override public void onResume() {
        super.onResume();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView text = new TextView(this);
        text.setTextSize(18);
        try {
            PackageInfo info = GmsCompatibility.installed(this);
            String mapping = GmsCompatibility.mapping(info);
            PackageInfo module = getPackageManager().getPackageInfo(getPackageName(), 0);
            text.setText("模块版本：" + module.versionName
                    + "（" + module.getLongVersionCode() + "）\n\n当前 Google Play 服务\n" + info.versionName
                    + "\n版本代码：" + info.getLongVersionCode()
                    + "\n\n" + (mapping == null
                    ? "尚未适配此构建。\n模块不会为此构建安装 GMS 混淆 Hook；不代表 Google 原生功能必然失效。地图修正独立运行。"
                    : "此构建已有适配映射。\n这不代表模块已启用或所有功能均已验证。请在 LSPosed 启用模块，勾选 Google Play 服务与 Find Hub，更新后重启。")
                    + "\n\n支持的完整 GMS 构建\n（完整版本名与版本代码须同时匹配）\n\n"
                    + GmsCompatibility.supportedBuilds()
                    + "\n\n仅适用于国际版 Xiaomi Tag，不适用于国行版 Xiaomi Tag。");
        } catch (Exception error) {
            text.setText("未能读取 Google Play 服务，请确认已安装。 ");
        }
        layout.addView(text);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Button enable = new Button(this);
            enable.setText("开启未知版本通知");
            enable.setOnClickListener(v -> requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
            layout.addView(enable);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        CompatibilityReceiver.check(this);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            onResume();
        }
    }
}
