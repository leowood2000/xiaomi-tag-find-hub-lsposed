package com.leowood.gmsfastpairdiagnostics;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
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
            text.setText("Google Play 服务\n" + info.versionName
                    + "\n版本代码：" + info.getLongVersionCode()
                    + "\n\n" + (mapping == null
                    ? "未知版本，需要更新模块。\n配对及云端混淆 Hook 已停用，地图修正独立运行。"
                    : "此版本已有适配。\n请在 LSPosed 启用模块并勾选 Google Play 服务与 Find Hub，更新后重启。")
                    + "\n\n已支持：26.26.34、26.36.35（指定构建）"
                    + "\n仅适用于国际版 Xiaomi Tag，不适用于国行 Tag。");
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
        setContentView(layout);
        CompatibilityReceiver.check(this);
    }
}
