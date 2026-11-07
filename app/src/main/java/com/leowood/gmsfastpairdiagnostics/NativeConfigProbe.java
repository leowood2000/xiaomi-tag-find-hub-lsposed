package com.leowood.gmsfastpairdiagnostics;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/** Temporary read-only probe for the current 26.43.30 research build. */
final class NativeConfigProbe {
    static void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("fysr",loader,"s",new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    log("debug authorization result="+(p.hasThrowable()?p.getThrowable():"passed"));
                }
            });
            for(String name:new String[]{"fytd","fyte"}) {
                XposedBridge.hookAllMethods(XposedHelpers.findClass(name,loader),"js",new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        Object task=p.args[0];
                        for(java.lang.reflect.Method m:task.getClass().getMethods()) {
                            if(m.getParameterTypes().length==0 && m.getReturnType()==Exception.class)
                                log("native API exception="+m.invoke(task));
                        }
                        log("native API success="+XposedHelpers.callMethod(task,"n"));
                    }
                });
            }
        } catch(Throwable e) { log("install probe error="+e); }
    }
    static void inspect(Application app, ClassLoader loader) {
        try {
            Class<?> config = XposedHelpers.findClass("kgkv", loader);
            Object raw = XposedHelpers.callMethod(XposedHelpers.newInstance(config), "l");
            log("raw enableDebugService=" + raw);
            Object fmd = XposedHelpers.newInstance(XposedHelpers.findClass("kbic",loader));
            log("raw FMD accessories="+XposedHelpers.callMethod(fmd,"n")
                    +" selfLocation="+XposedHelpers.callMethod(fmd,"v")
                    +" clientActions="+XposedHelpers.callMethod(fmd,"w")
                    +" finder="+XposedHelpers.callStaticMethod(XposedHelpers.findClass("clya",loader),"a"));
            String[] components={"nearby.discovery.fastpair.HalfSheetActivity",
                    "nearby.discovery.fastpair.slice.FastPairSliceProvider",
                    "nearby.discovery.service.DiscoveryService","nearby.discovery.devices.DevicesListActivity"};
            for(String component:components) log("component "+component+" state="+
                    app.getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(
                            "com.google.android.gms","com.google.android.gms."+component)));
            log("raw SPOT getter="+XposedHelpers.callMethod(
                    XposedHelpers.newInstance(XposedHelpers.findClass("kein",loader)),"az")
                    +" unhooked facade="+XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("keil",loader),"P"));
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
