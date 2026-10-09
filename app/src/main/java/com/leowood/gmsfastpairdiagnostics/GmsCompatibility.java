package com.leowood.gmsfastpairdiagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;

public final class GmsCompatibility {
    private GmsCompatibility() {}

    public static String mapping(PackageInfo info) {
        long code = info.getLongVersionCode();
        if (code == 262634035L && "26.26.34 (260400-945364269)".equals(info.versionName))
            return "26.26.34";
        if (code == 263635035L && "26.36.35 (260400-991383798)".equals(info.versionName))
            return "26.36.35";
        if (code == 263737035L && "26.37.37 (260400-994713346)".equals(info.versionName))
            return "26.37.37";
        return null;
    }

    public static PackageInfo installed(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo("com.google.android.gms", 0);
    }
}
