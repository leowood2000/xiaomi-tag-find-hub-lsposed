package com.leowood.gmsfastpairdiagnostics;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Diagnostics for the exact obfuscated classes shipped in Google Play services
 * 26.26.34 (260400-945364269).
 *
 * This version deliberately does not change a return value. It records the
 * decision chain so the actual rejecting predicate can be identified safely.
 */
public final class FastPairHook implements IXposedHookLoadPackage {
    private static final String TAG = "[GmsFastPairDiag] ";
    private static final String TARGET_MODEL_ID = "15d23e";
    private static final Set<String> PROTECTED_FAST_PAIR_COMPONENTS =
            new HashSet<>(Arrays.asList(
                    "com.google.android.gms.nearby.discovery.fastpair.HalfSheetActivity",
                    "com.google.android.gms.nearby.discovery.fastpair.slice.FastPairSliceProvider",
                    "com.google.android.gms.nearby.discovery.service.DiscoveryService",
                    "com.google.android.gms.nearby.discovery.devices.DevicesListActivity"));
    private static final int DEVICE_NOT_SUPPORTED = 11;
    private static final int SUCCESS = 15;
    private static final Pattern MAC =
            Pattern.compile("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");
    private static final Set<String> HOOKED = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.google.android.apps.adm".equals(lpparam.packageName)) {
            FindHubMapHook.install(lpparam.classLoader, lpparam.processName);
            return;
        }

        if (!"com.google.android.gms".equals(lpparam.packageName)) {
            return;
        }

        log("loaded process=" + lpparam.processName);
        keepHalfSheetComponentEnabled();
        hookSpotClientActions(lpparam.classLoader);
        hookSpotFastPairServerFlag(lpparam.classLoader);
        hookSelfLocationReportingFlag(lpparam.classLoader);
        hookFastPairSpotIntegrationFlag(lpparam.classLoader);
        hookFinalDecision(lpparam.classLoader);
        hookLocatorTagEligibility(lpparam.classLoader);
        hookEligibilityPredicates(lpparam.classLoader);
        hookInitialPairingObserver(lpparam.classLoader);
    }

    /**
     * Find Hub web commands arrive through GCMReceiverChimeraService. On the
     * affected China configuration, the service rejects the signed command
     * before BLE handling because ccnl.a() reads the underlying server flags
     * directly instead of calling the jwbd wrapper methods hooked below.
     *
     * A second gate, jwbd.k(), controls the signed SPOT client-action handler.
     * Signature verification remains in Google's command parser; these hooks
     * only allow the normal receiver and handler to run.
     */
    private static void hookSpotClientActions(ClassLoader loader) {
        hookBooleanGate(
                loader,
                "ccnl",
                "a",
                "finderUseCases",
                "Find Hub GCM receiver Finder-use-case gate");
        hookBooleanGate(
                loader,
                "jwbd",
                "k",
                "enableSpotClientActionsHandler",
                "Find Hub flag enable_spot_client_actions_handler");
    }

    private static void hookBooleanGate(
            ClassLoader loader,
            String className,
            String methodName,
            String keySuffix,
            String label) {
        Class<?> type = XposedHelpers.findClassIfExists(className, loader);
        if (type == null) {
            log(className + " not found for " + keySuffix);
            return;
        }

        String key = type.getName() + "#" + methodName + ":" + keySuffix;
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                type,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Boolean)) {
                            return;
                        }
                        boolean original = (Boolean) param.getResult();
                        if (!original) {
                            param.setResult(true);
                        }
                        log(label + " original=" + original + " effective=true");
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    /**
     * ChangeFindMyDeviceSettings rejects enabling Last Known Location when
     * jwbd.j() is false, throwing "Self location reporting is disabled."
     * jwbd.j() reads:
     *
     * EnableFindMyDeviceModule__enable_self_location_reporting
     */
    private static void hookSelfLocationReportingFlag(ClassLoader loader) {
        Class<?> flags = XposedHelpers.findClassIfExists("jwbd", loader);
        if (flags == null) {
            log("jwbd Find My Device flags not found for self location reporting");
            return;
        }

        String key = flags.getName() + "#j:enableSelfLocationReporting";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                flags,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Boolean)) {
                            return;
                        }
                        boolean original = (Boolean) param.getResult();
                        if (!original) {
                            param.setResult(true);
                        }
                        log("Find Hub flag enable_self_location_reporting original="
                                + original + " effective=true");
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    /**
     * dqqi.C(dreq), used again by the locator-tag success screen, starts with
     * jyxk.K(). That method reads enable_fast_pair_spot_integration, whose
     * default/current value on the CN build is false. Bypassing only the
     * earlier eligibility result is insufficient because the success screen
     * performs this independent check after the BLE pairing has completed.
     */
    private static void hookFastPairSpotIntegrationFlag(ClassLoader loader) {
        Class<?> flags = XposedHelpers.findClassIfExists("jyxk", loader);
        if (flags == null) {
            log("jyxk Fast Pair flags not found");
            return;
        }

        String key = flags.getName() + "#K:enableFastPairSpotIntegration";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                flags,
                "K",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Boolean)) {
                            return;
                        }
                        boolean original = (Boolean) param.getResult();
                        if (!original) {
                            param.setResult(true);
                        }
                        log("Fast Pair flag enable_fast_pair_spot_integration original="
                                + original + " effective=true");
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    /**
     * FastPairApiChimeraService.a(dcrx, GetServiceRequest) only publishes the
     * SPOT binder when jwbd.g() is true. In GMS 26.26.34 this method reads:
     *
     * EnableFindMyDeviceModule__enable_fast_pair_accessories
     *
     * CN device policy currently supplies false, causing the broker to return
     * API_UNAVAILABLE before any SPOT method can run.
     */
    private static void hookSpotFastPairServerFlag(ClassLoader loader) {
        Class<?> flags = XposedHelpers.findClassIfExists("jwbd", loader);
        if (flags == null) {
            log("jwbd Find My Device flags not found");
            return;
        }

        String key = flags.getName() + "#g:enableSpotFastPair";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                flags,
                "g",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Boolean)) {
                            return;
                        }
                        boolean original = (Boolean) param.getResult();
                        if (!original) {
                            param.setResult(true);
                        }
                        log("SPOT server flag enable_fast_pair_accessories original="
                                + original + " effective=true");
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void keepHalfSheetComponentEnabled() {
        Class<?> packageManager = XposedHelpers.findClassIfExists(
                "android.app.ApplicationPackageManager",
                null);
        if (packageManager == null) {
            log("ApplicationPackageManager not found");
            return;
        }

        String key = packageManager.getName() + "#setComponentEnabledSetting";
        if (!HOOKED.add(key)) {
            return;
        }

        XposedHelpers.findAndHookMethod(
                packageManager,
                "setComponentEnabledSetting",
                ComponentName.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ComponentName component = (ComponentName) param.args[0];
                        int state = (Integer) param.args[1];
                        if (component == null
                                || !"com.google.android.gms".equals(component.getPackageName())
                                || !PROTECTED_FAST_PAIR_COMPONENTS.contains(component.getClassName())
                                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                            return;
                        }

                        log("blocked GMS from disabling " + component.getClassName()
                                + " state=" + state);
                        param.setResult(null);
                    }
                });

        log("hooked " + key);
    }

    private static void hookFinalDecision(ClassLoader loader) {
        Class<?> manager = XposedHelpers.findClassIfExists("drgg", loader);
        if (manager == null) {
            log("drgg not found; this GMS build uses different obfuscation");
            return;
        }

        hookAll(manager, "g", true);
    }

    private static void hookLocatorTagEligibility(ClassLoader loader) {
        Class<?> locatorHandler = XposedHelpers.findClassIfExists("drhl", loader);
        if (locatorHandler == null) {
            log("drhl locator-tag handler not found");
            return;
        }

        String key = locatorHandler.getName() + "#e:bypass";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                locatorHandler,
                "e",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object request = findRequest(param.args);
                        if (!isTargetTag(request) || param.hasThrowable()) {
                            return;
                        }

                        Object original = param.getResult();
                        log("locator eligibility original=" + safe(original)
                                + " request=" + describeRequest(request));
                        if (original instanceof Integer
                                && ((Integer) original) == DEVICE_NOT_SUPPORTED) {
                            param.setResult(SUCCESS);
                            log("bypass drhl#e DEVICE_NOT_SUPPORTED -> SUCCESS"
                                    + " model=" + TARGET_MODEL_ID);
                        }
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookEligibilityPredicates(ClassLoader loader) {
        Class<?> halfSheetPolicy = XposedHelpers.findClassIfExists("dqqi", loader);
        if (halfSheetPolicy != null) {
            // These methods form the decision chain called by drgg.g(dreq).
            for (String name : new String[]{"N", "F", "H", "G", "B", "E"}) {
                hookAll(halfSheetPolicy, name, false);
            }
        } else {
            log("dqqi not found");
        }

        Class<?> manager = XposedHelpers.findClassIfExists("drgg", loader);
        if (manager != null) {
            for (String name : new String[]{"h", "e", "j"}) {
                hookAll(manager, name, false);
            }
        }

        Class<?> environment = XposedHelpers.findClassIfExists("fsdw", loader);
        if (environment != null) {
            hookAll(environment, "b", false);
        }
    }

    private static void hookInitialPairingObserver(ClassLoader loader) {
        Class<?> checker = XposedHelpers.findClassIfExists("dpyf", loader);
        if (checker == null) {
            log("dpyf (InitialPairingDeviceChecker) not found");
            return;
        }

        // dpyf.a() only obtains the Bluetooth/discovery state used by the
        // cached-device check. It is observed here to prove ordering.
        hookAll(checker, "a", false);
        hookAll(checker, "i", false);
    }

    private static synchronized void hookAll(
            Class<?> clazz, String methodName, boolean includeStack) {
        String key = clazz.getName() + "#" + methodName;
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                clazz,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isRelevant(param)) {
                            return;
                        }

                        StringBuilder message = new StringBuilder();
                        message.append(signature((Method) param.method))
                                .append(" result=")
                                .append(safe(param.getResult()));

                        Object request = findRequest(param.args);
                        if (request != null) {
                            message.append(" request=").append(describeRequest(request));
                        }

                        if (param.hasThrowable()) {
                            message.append(" throwable=").append(param.getThrowable());
                        }

                        log(message.toString());
                        if (includeStack) {
                            log("decision stack:\n"
                                    + android.util.Log.getStackTraceString(new Throwable()));
                        }
                    }
                });

        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static boolean isRelevant(XC_MethodHook.MethodHookParam param) {
        if (param.method.getDeclaringClass().getName().equals("dpyf")) {
            return true;
        }
        if (param.method.getDeclaringClass().getName().equals("fsdw")) {
            return true;
        }
        return findRequest(param.args) != null;
    }

    private static Object findRequest(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && "dreq".equals(arg.getClass().getName())) {
                return arg;
            }
        }
        return null;
    }

    private static boolean isTargetTag(Object request) {
        if (request == null) {
            return false;
        }
        try {
            Field modelId = request.getClass().getDeclaredField("e");
            modelId.setAccessible(true);
            Object value = modelId.get(request);
            return value != null && TARGET_MODEL_ID.equalsIgnoreCase(String.valueOf(value));
        } catch (Throwable error) {
            log("cannot read target model id: " + error);
            return false;
        }
    }

    private static String describeRequest(Object request) {
        StringBuilder out = new StringBuilder("{");
        Field[] fields = request.getClass().getDeclaredFields();
        int written = 0;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (!(type == String.class
                    || type == int.class
                    || type == boolean.class
                    || type == long.class)) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (written++ > 0) {
                    out.append(", ");
                }
                out.append(field.getName())
                        .append('=')
                        .append(safe(field.get(request)));
                if (written >= 24) {
                    out.append(", …");
                    break;
                }
            } catch (Throwable ignored) {
                // Obfuscated GMS builds may deny access to individual fields.
            }
        }
        return out.append('}').toString();
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName()
                + "#"
                + method.getName()
                + Arrays.toString(method.getParameterTypes());
    }

    private static String safe(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        text = MAC.matcher(text).replaceAll("XX:XX:XX:XX:XX:XX");
        if (text.length() > 300) {
            return text.substring(0, 300) + "…";
        }
        return text;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
