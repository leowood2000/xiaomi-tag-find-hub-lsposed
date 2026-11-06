package com.leowood.gmsfastpairdiagnostics;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Temporary read-only probe for the current 26.43.30 research build. */
final class NativeConfigProbe {
    static void inspect(Application app, ClassLoader loader) {
        try {
            Class<?> config = XposedHelpers.findClass("kgkv", loader);
            Object raw = XposedHelpers.callMethod(XposedHelpers.newInstance(config), "l");
            log("raw enableDebugService=" + raw);
            Object registry = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("hjsn", loader), "a");
            Map<?,?> stores = (Map<?,?>) XposedHelpers.getObjectField(registry, "b");
            Object entry = stores.get("com.google.android.gms.phenotype");
            if (entry != null) {
                Object store = XposedHelpers.callMethod(entry, "d");
                Object snapshot = XposedHelpers.getObjectField(store, "c");
                log("store DE=" + XposedHelpers.getBooleanField(store,"g"));
                Object manager = XposedHelpers.getObjectField(store,"d");
                Object shared = XposedHelpers.callMethod(XposedHelpers.getObjectField(manager,"e"),"c",true);
                Object fileKey = XposedHelpers.newInstance(XposedHelpers.findClass("hjoq",loader),
                        XposedHelpers.getObjectField(shared,"c"),"com.google.android.gms.phenotype","");
                log("shared root="+XposedHelpers.getObjectField(shared,"e")
                        +" dir="+XposedHelpers.getObjectField(shared,"d")
                        +" leaf="+XposedHelpers.callMethod(fileKey,"a"));
                if (snapshot != null) {
                    Map<?,?> flags = (Map<?,?>) XposedHelpers.getObjectField(snapshot,"h");
                    Object info = XposedHelpers.getObjectField(snapshot,"i");
                    log("snapshot debug="+flags.get("enableDebugService")
                            +" status="+XposedHelpers.getIntField(info,"b")
                            +","+XposedHelpers.getIntField(info,"c"));
                }
            }
            try (Cursor cursor = app.getContentResolver().query(Uri.parse(
                    "content://com.google.android.gms.phenotype/com.google.android.gms.phenotype"),null,null,null,null)) {
                boolean found = false;
                if(cursor != null) while(cursor.moveToNext()) {
                    if("enableDebugService".equals(cursor.getString(0))) {
                        found = true; log("provider debug="+cursor.getString(1));
                    }
                }
                log("provider cursor="+(cursor!=null)+" debugPresent="+found);
            }
        } catch (Throwable e) { log("probe error="+e); }
    }
    private static void log(String value) { XposedBridge.log("[NativeConfigProbe] "+value); }
}
