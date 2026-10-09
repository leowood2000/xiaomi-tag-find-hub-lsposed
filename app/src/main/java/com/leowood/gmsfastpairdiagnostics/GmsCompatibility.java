package com.leowood.gmsfastpairdiagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;

public final class GmsCompatibility {
    private GmsCompatibility() {}

    private static final String[][] BUILDS = {
            {"26.26.34", "26.26.34 (260400-945364269)", "262634035"},
            {"26.36.35", "26.36.35 (260400-991383798)", "263635035"},
            {"26.37.37", "26.37.37 (260400-994713346)", "263737035"}
    };

    public static String mapping(PackageInfo info) {
        long code = info.getLongVersionCode();
        for (String[] build : BUILDS) {
            if (code == Long.parseLong(build[2]) && build[1].equals(info.versionName))
                return build[0];
        }
        return null;
    }

    public static String supportedBuilds() {
        StringBuilder text = new StringBuilder();
        for (String[] build : BUILDS) {
            if (text.length() > 0) text.append("\n\n");
            text.append(build[1]).append("\n版本代码：").append(build[2]);
        }
        return text.toString();
    }

    public static PackageInfo installed(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo("com.google.android.gms", 0);
    }
}
