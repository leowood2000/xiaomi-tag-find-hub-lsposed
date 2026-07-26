package com.leowood.gmsfastpairdiagnostics;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Network;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
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
    private static volatile long CLOUD_UPLOAD_ACTIVE_UNTIL;

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
        hookSpotFastPairServerFlag(lpparam.classLoader);
        hookSelfLocationReportingFlag(lpparam.classLoader);
        hookFastPairSpotIntegrationFlag(lpparam.classLoader);
        hookFmdnSettingsUiFlag(lpparam.classLoader);
        hookFindMyDeviceSettingsResponse(lpparam.classLoader);
        hookProvisioningState(lpparam.classLoader);
        hookUploadSchedulerParameters(lpparam.classLoader);
        hookOwnerSightingFastUpload(lpparam.classLoader);
        hookCloudUploadNetworkBinding();
        hookLocationReportPipeline(lpparam.classLoader);
        hookOwnerUploadResult(lpparam.classLoader);
        hookOwnedDeviceSyncResult(lpparam.classLoader);
        hookFinalDecision(lpparam.classLoader);
        hookLocatorTagEligibility(lpparam.classLoader);
        hookEligibilityPredicates(lpparam.classLoader);
        hookInitialPairingObserver(lpparam.classLoader);
        if ("com.google.android.gms".equals(lpparam.processName)) {
            scheduleForcedDeviceSync(lpparam.classLoader);
        }
    }

    /**
     * Diagnostic only: ask GMS's own scheduler to run the same forced device
     * sync used by its internal one-off task. This does not synthesize devices;
     * it only refreshes the server-owned device/EID cache for each Google
     * account already present on the phone.
     */
    private static void scheduleForcedDeviceSync(final ClassLoader loader) {
        String key = "Instrumentation#callApplicationOnCreate:forceDeviceSync";
        if (!HOOKED.add(key)) {
            return;
        }
        XposedBridge.hookAllMethods(
                Instrumentation.class,
                "callApplicationOnCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null
                                || param.args.length == 0
                                || !(param.args[0] instanceof Application)) {
                            return;
                        }
                        final Application application = (Application) param.args[0];
                        new Thread(
                                () -> {
                                    try {
                                        Thread.sleep(5000L);
                                        Account[] accounts = AccountManager.get(application)
                                                .getAccountsByType("com.google");
                                        Class<?> schedulerClass =
                                                XposedHelpers.findClass("dekn", loader);
                                        Object scheduler = XposedHelpers.callStaticMethod(
                                                schedulerClass, "a", application);
                                        Class<?> schedulingUtil =
                                                XposedHelpers.findClass("ccmj", loader);
                                        for (Account account : accounts) {
                                            XposedHelpers.callStaticMethod(
                                                    schedulingUtil, "j", scheduler, account);
                                            log("scheduled forced device sync account="
                                                    + account.name);
                                            runDeviceSyncDirectly(
                                                    loader, application, account);
                                        }
                                        runSelfRegistrationSync(loader, application);
                                    } catch (Throwable throwable) {
                                        log("forced device sync scheduling failed="
                                                + throwable.getClass().getSimpleName()
                                                + ": " + safe(throwable.getMessage()));
                                    }
                                },
                                "FmdnForceDeviceSync").start();
                    }
                });
        log("hooked " + key);
    }

    /**
     * The Xiaomi build is not dispatching the one-off GMS task promptly. Run
     * the same DeviceSyncService implementation directly so its real RPC,
     * encryption and cache-writing chain remain unchanged.
     */
    private static void runDeviceSyncDirectly(
            ClassLoader loader, Application application, Account account) {
        try {
            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.google.android.gms.findmydevice.spot.sync.DeviceSyncService",
                    loader);
            Object service = XposedHelpers.newInstance(serviceClass);
            XposedHelpers.callMethod(service, "setModuleContext", application);
            Object accountFactory = XposedHelpers.getObjectField(service, "f");
            Object accountDependencies =
                    XposedHelpers.callMethod(accountFactory, "a", account);
            Object future = XposedHelpers.callMethod(
                    service, "e", account, accountDependencies);
            log("started direct device sync account=" + account.name
                    + " future=" + future.getClass().getSimpleName());
        } catch (Throwable throwable) {
            log("direct device sync failed=" + throwable.getClass().getSimpleName()
                    + ": " + safe(throwable.getMessage()));
        }
    }

    private static void runSelfRegistrationSync(
            ClassLoader loader, Application application) {
        try {
            Class<?> serviceClass = XposedHelpers.findClass(
                    "com.google.android.gms.findmydevice.spot.sync."
                            + "SelfReportingRegistrationAndOwnerKeySyncService",
                    loader);
            Object service = XposedHelpers.newInstance(serviceClass);
            XposedHelpers.callMethod(service, "setModuleContext", application);
            Bundle extras = new Bundle();
            extras.putBoolean("throttle", false);
            Class<?> taskClass = XposedHelpers.findClass("demp", loader);
            Object task = XposedHelpers.newInstance(
                    taskClass, "diag_force_self_registration", extras);
            Object future = XposedHelpers.callMethod(service, "d", task);
            log("started direct self-registration sync future="
                    + future.getClass().getSimpleName());
        } catch (Throwable throwable) {
            log("direct self-registration sync failed="
                    + throwable.getClass().getSimpleName()
                    + ": " + safe(throwable.getMessage()));
        }
    }

    /**
     * ccko is the first continuation that receives GetOwnedDevicesResponse.
     * Record list sizes only, avoiding identifiers and key material.
     */
    private static void hookOwnedDeviceSyncResult(ClassLoader loader) {
        Class<?> continuation = XposedHelpers.findClassIfExists("ccko", loader);
        if (continuation == null) {
            log("owned-device sync continuation ccko not found");
            return;
        }
        String key = continuation.getName() + "#a:ownedDeviceCounts";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                continuation,
                "a",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null
                                || param.args.length == 0
                                || param.args[0] == null
                                || !"isbs".equals(param.args[0].getClass().getName())) {
                            return;
                        }
                        Object response = param.args[0];
                        log("owned-device sync response"
                                + " computedEidDevices="
                                + collectionSize(readField(response, "c"))
                                + " precomputedEidDevices="
                                + collectionSize(readField(response, "d"))
                                + " otherDevices=" + collectionSize(readField(response, "e"))
                                + " keyData=" + collectionSize(readField(response, "g"))
                                + " deviceTypeCodes="
                                + collectionValues(readField(response, "h"))
                                + " computedBeaconTypes="
                                + collectionFieldValues(readField(response, "c"), "l")
                                + " identityHashes="
                                + computedIdentityHashes(
                                        loader, readField(response, "c")));
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    /**
     * The settings activity chooses the legacy page unless kbim.m() is true.
     * Exposing the complete FMDN page is diagnostic only: it does not alter the
     * stored network mode or claim that the phone is provisioned.
     */
    private static void hookFmdnSettingsUiFlag(ClassLoader loader) {
        Class<?> flags = XposedHelpers.findClassIfExists("kbim", loader);
        if (flags == null) {
            log("kbim FMDN settings UI gate not found");
            return;
        }

        String key = flags.getName() + "#m:openFmdnSettings";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                flags,
                "m",
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
                        log("FMDN settings UI gate original=" + original + " effective=true");
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookFindMyDeviceSettingsResponse(ClassLoader loader) {
        Class<?> response = XposedHelpers.findClassIfExists(
                "com.google.android.gms.findmydevice.spot.GetFindMyDeviceSettingsResponse",
                loader);
        if (response == null) {
            log("GetFindMyDeviceSettingsResponse not found");
            return;
        }

        String key = response.getName() + "#constructors:diagnostic";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllConstructors(
                response,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("FMDN settings response=" + describePrimitiveFields(param.thisObject));
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    /**
     * cboc.j(gtlt) is the provisioning predicate used before a self-location
     * report is accepted. Only non-sensitive scalar state is logged.
     */
    private static void hookProvisioningState(ClassLoader loader) {
        Class<?> provisioning = XposedHelpers.findClassIfExists("cboc", loader);
        if (provisioning == null) {
            log("cboc provisioning state reader not found");
            return;
        }

        String key = provisioning.getName() + "#j:provisioningDiagnostic";
        if (!HOOKED.add(key)) {
            return;
        }

        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                provisioning,
                "j",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Boolean)) {
                            return;
                        }
                        Object state = param.args != null && param.args.length > 0
                                ? param.args[0] : null;
                        log("FMDN provisioned=" + param.getResult()
                                + " state=" + describeNamedFields(state, "b", "c", "g")
                                + " nestedK=" + describeNamedNestedField(state, "k", "b"));
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookLocationReportPipeline(ClassLoader loader) {
        for (String className : new String[]{
                "com.google.android.gms.findmydevice.spot.locationreporting."
                        + "LocationAssigningIntentOperation",
                "com.google.android.gms.findmydevice.spot.locationreporting."
                        + "LocationReportingServiceIntentOperation",
                "com.google.android.gms.findmydevice.spot.locationreporting."
                        + "LocationReportUploadIntentOperation"}) {
            hookIntentOperation(loader, className);
        }
        hookPipelineMethod(loader, "ccdj", "c", "sighting received");
        hookPipelineMethod(loader, "ccdj", "h", "sighting aggregation");
        hookPipelineMethod(loader, "cbth", "d", "upload scheduling");
    }

    /**
     * UploadOwnerScans can complete successfully while acknowledging no
     * sightings. Record both the metrics status/count and the size of the
     * server response field consumed by LocationReportUploadIntentOperation.
     */
    private static void hookOwnerUploadResult(ClassLoader loader) {
        Class<?> operation = XposedHelpers.findClassIfExists(
                "com.google.android.gms.findmydevice.spot.locationreporting."
                        + "LocationReportUploadIntentOperation",
                loader);
        if (operation != null) {
            String batchKey = operation.getName() + "#a:ownerBatchShape";
            if (HOOKED.add(batchKey)) {
                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                        operation,
                        "a",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null
                                        || param.args.length != 2
                                        || param.args[0] == null
                                        || !"isct".equals(param.args[0].getClass().getName())) {
                                    return;
                                }
                                log("owner upload batch=" + describeOwnerBatch(param.args[0])
                                        + " accountCandidates="
                                        + collectionSize(param.args[1]));
                            }
                        });
                log("hooked " + batchKey + " overloads=" + unhooks.size());
            }

            String accountKey = operation.getName() + "#c:ownerUploadAccount";
            if (HOOKED.add(accountKey)) {
                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                        operation,
                        "c",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null
                                        && param.args.length == 1
                                        && param.args[0] instanceof android.accounts.Account) {
                                    android.accounts.Account account =
                                            (android.accounts.Account) param.args[0];
                                    log("owner upload account=" + safe(account.name)
                                            + " type=" + safe(account.type));
                                }
                            }
                        });
                log("hooked " + accountKey + " overloads=" + unhooks.size());
            }

            String metricsKey = operation.getName() + "#d:ownerUploadResult";
            if (HOOKED.add(metricsKey)) {
                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                        operation,
                        "d",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args == null || param.args.length != 4) {
                                    return;
                                }
                                log("owner upload metrics type="
                                        + describeNamedFields(param.args[0], "a")
                                        + " trigger=" + safe(param.args[1])
                                        + " result=" + describeNamedFields(param.args[2], "a")
                                        + " attempted=" + safe(param.args[3]));
                            }
                        });
                log("hooked " + metricsKey + " overloads=" + unhooks.size());
            }
        }

        Class<?> successMapper = XposedHelpers.findClassIfExists("cbsu", loader);
        if (successMapper == null) {
            log("cbsu UploadOwnerScans success mapper not found");
            return;
        }
        String responseKey = successMapper.getName() + "#apply:ownerUploadResponse";
        if (!HOOKED.add(responseKey)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                successMapper,
                "apply",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1) {
                            return;
                        }
                        Object response = param.args[0];
                        if (response == null || !"isep".equals(response.getClass().getName())) {
                            return;
                        }
                        log("UploadOwnerScans response=" + describeCollectionField(response, "b"));
                    }
                });
        log("hooked " + responseKey + " overloads=" + unhooks.size());
    }

    /**
     * GMS explicitly binds the FMDN upload socket to the physical Wi-Fi
     * network. That produces fwmark 0x66 and bypasses Android's per-UID VPN
     * rule even though the GMS UID is included in the VPN. During the narrow
     * cloud-upload window, leave sockets unbound so normal routing selects the
     * VPN when one exists (or the ordinary default network when it does not).
     */
    private static void hookCloudUploadNetworkBinding() {
        String key = Network.class.getName() + "#bindSocket:fmdnUploadRouting";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                Network.class,
                "bindSocket",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (SystemClock.elapsedRealtime() > CLOUD_UPLOAD_ACTIVE_UNTIL) {
                            return;
                        }
                        log("prevented physical-network socket binding during FMDN upload"
                                + " network=" + safe(param.thisObject));
                        param.setResult(null);
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    /**
     * On this HyperOS build the scheduled GMS task is accepted but never
     * invokes LocationReportUploadIntentOperation. Reuse Google's existing
     * fast-executor path so the same encrypted batch is uploaded after the
     * configured short delay.
     */
    private static void hookOwnerSightingFastUpload(ClassLoader loader) {
        Class<?> scheduler = XposedHelpers.findClassIfExists("cbth", loader);
        if (scheduler == null) {
            log("cbth upload scheduler not found for fast-path workaround");
            return;
        }
        String key = scheduler.getName() + "#d:fastUploadWorkaround";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                scheduler,
                "d",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null
                                || param.args.length < 2
                                || !(param.args[1] instanceof Boolean)
                                || (Boolean) param.args[1]) {
                            return;
                        }
                        param.args[1] = true;
                        log("enabled fast-executor upload for owner sighting");
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookUploadSchedulerParameters(ClassLoader loader) {
        Class<?> locationFlags = XposedHelpers.findClassIfExists("jwch", loader);
        if (locationFlags == null) {
            log("jwch location-report flags not found");
        } else {
            hookUploadDelayForTest(locationFlags);
            hookScalarResult(locationFlags, "p", "fast executor upload delay seconds");
        }

        Class<?> behaviorFlags = XposedHelpers.findClassIfExists("jwbw", loader);
        if (behaviorFlags == null) {
            log("jwbw location-report behavior flags not found");
        } else {
            hookScalarResult(behaviorFlags, "h", "recent crowdsourced sighting policy");
            hookScalarResult(behaviorFlags, "f", "validated network policy");
        }
    }

    /**
     * Temporary diagnostic override. Nearby owner sightings arrive more often
     * than the stock 300 second task delay, so repeatedly replacing the same
     * task may starve it indefinitely. Thirty seconds is short enough to prove
     * whether the upload operation itself is healthy.
     */
    private static void hookUploadDelayForTest(Class<?> locationFlags) {
        String key = locationFlags.getName() + "#r:thirtySecondDiagnostic";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                locationFlags,
                "r",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !(param.getResult() instanceof Number)) {
                            return;
                        }
                        long original = ((Number) param.getResult()).longValue();
                        param.setResult(30L);
                        log("scheduler flag GMS task upload delay seconds original="
                                + original + " effective=30 diagnostic");
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookScalarResult(
            Class<?> clazz, String methodName, String label) {
        String key = clazz.getName() + "#" + methodName + ":scalarDiagnostic";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                clazz,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable()) {
                            log("scheduler flag " + label + "=" + safe(param.getResult()));
                        }
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookIntentOperation(ClassLoader loader, String className) {
        Class<?> operation = XposedHelpers.findClassIfExists(className, loader);
        if (operation == null) {
            log(className + " not found");
            return;
        }
        String key = operation.getName() + "#onHandleIntent:diagnostic";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                operation,
                "onHandleIntent",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ("LocationReportUploadIntentOperation"
                                .equals(operation.getSimpleName())) {
                            CLOUD_UPLOAD_ACTIVE_UNTIL =
                                    SystemClock.elapsedRealtime() + 120_000L;
                            log("FMDN cloud upload routing window opened");
                        }
                        log("pipeline enter " + operation.getSimpleName()
                                + " action=" + findIntentAction(param.args));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("pipeline exit " + operation.getSimpleName()
                                + (param.hasThrowable()
                                ? " throwable=" + param.getThrowable() : " ok"));
                    }
                });
        log("hooked " + key + " overloads=" + unhooks.size());
    }

    private static void hookPipelineMethod(
            ClassLoader loader, String className, String methodName, String label) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, loader);
        if (clazz == null) {
            log(className + " " + label + " class not found");
            return;
        }
        String key = clazz.getName() + "#" + methodName + ":pipelineDiagnostic";
        if (!HOOKED.add(key)) {
            return;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(
                clazz,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder out = new StringBuilder("pipeline ")
                                .append(label)
                                .append(" args=");
                        if (param.args == null) {
                            out.append("null");
                        } else {
                            out.append('[');
                            for (int i = 0; i < param.args.length; i++) {
                                if (i > 0) {
                                    out.append(", ");
                                }
                                Object arg = param.args[i];
                                if (arg instanceof Location) {
                                    Location location = (Location) arg;
                                    out.append("Location(lat=")
                                            .append(location.getLatitude())
                                            .append(", lon=")
                                            .append(location.getLongitude())
                                            .append(", accuracy=")
                                            .append(location.getAccuracy())
                                            .append(", time=")
                                            .append(location.getTime())
                                            .append(')');
                                } else if (arg instanceof Collection) {
                                    out.append(arg.getClass().getSimpleName())
                                            .append("(size=")
                                            .append(((Collection<?>) arg).size())
                                            .append(')');
                                } else if (arg instanceof Boolean
                                        || arg instanceof Number
                                        || arg == null) {
                                    out.append(safe(arg));
                                } else {
                                    out.append(arg.getClass().getSimpleName());
                                }
                            }
                            out.append(']');
                        }
                        log(out.toString());
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) {
                            log("pipeline " + label + " throwable=" + param.getThrowable());
                        }
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

    private static String findIntentAction(Object[] args) {
        if (args == null) {
            return "null";
        }
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return safe(((Intent) arg).getAction());
            }
        }
        return "none";
    }

    private static String describePrimitiveFields(Object value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.getClass().getSimpleName()).append('{');
        int written = 0;
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            try {
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                if (type.isPrimitive() || fieldValue == null) {
                    if (written++ > 0) {
                        out.append(", ");
                    }
                    out.append(field.getName()).append('=').append(safe(fieldValue));
                } else if ("com.google.android.gms.findmydevice.spot.FindMyDeviceNetworkSettings"
                        .equals(type.getName())) {
                    if (written++ > 0) {
                        out.append(", ");
                    }
                    out.append(field.getName())
                            .append('=')
                            .append(describePrimitiveFields(fieldValue));
                }
            } catch (Throwable ignored) {
                // Diagnostics must never break GMS if a field becomes inaccessible.
            }
        }
        return out.append('}').toString();
    }

    private static String describeNamedFields(Object value, String... names) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("{");
        int written = 0;
        for (String name : names) {
            try {
                Field field = value.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                if (written++ > 0) {
                    out.append(", ");
                }
                out.append(name).append('=');
                if (fieldValue == null
                        || field.getType().isPrimitive()
                        || fieldValue instanceof Number
                        || fieldValue instanceof Boolean) {
                    out.append(safe(fieldValue));
                } else {
                    out.append(fieldValue.getClass().getSimpleName());
                }
            } catch (Throwable ignored) {
                // Obfuscation may change individual field names.
            }
        }
        return out.append('}').toString();
    }

    private static String describeNamedNestedField(
            Object value, String outerName, String innerName) {
        if (value == null) {
            return "null";
        }
        try {
            Field outer = value.getClass().getDeclaredField(outerName);
            outer.setAccessible(true);
            Object nested = outer.get(value);
            return describeNamedFields(nested, innerName);
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    private static String describeCollectionField(Object value, String fieldName) {
        if (value == null) {
            return "null";
        }
        try {
            Field field = value.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object fieldValue = field.get(value);
            if (fieldValue instanceof Collection) {
                Collection<?> collection = (Collection<?>) fieldValue;
                return value.getClass().getSimpleName()
                        + "{"
                        + fieldName
                        + ".type="
                        + fieldValue.getClass().getSimpleName()
                        + ", "
                        + fieldName
                        + ".size="
                        + collection.size()
                        + "}";
            }
            return value.getClass().getSimpleName()
                    + "{"
                    + fieldName
                    + "="
                    + safe(fieldValue)
                    + "}";
        } catch (Throwable throwable) {
            return value.getClass().getSimpleName()
                    + "{"
                    + fieldName
                    + ".error="
                    + throwable.getClass().getSimpleName()
                    + "}";
        }
    }

    private static int collectionSize(Object value) {
        return value instanceof Collection ? ((Collection<?>) value).size() : -1;
    }

    private static String collectionValues(Object value) {
        if (!(value instanceof Collection)) {
            return "unavailable";
        }
        return safe(((Collection<?>) value).toString());
    }

    private static String collectionFieldValues(Object value, String fieldName) {
        if (!(value instanceof Collection)) {
            return "unavailable";
        }
        StringBuilder out = new StringBuilder("[");
        int written = 0;
        for (Object item : (Collection<?>) value) {
            if (written++ > 0) {
                out.append(',');
            }
            out.append(safe(readField(item, fieldName)));
        }
        return out.append(']').toString();
    }

    private static String computedIdentityHashes(ClassLoader loader, Object value) {
        if (!(value instanceof Collection)) {
            return "unavailable";
        }
        StringBuilder out = new StringBuilder("[");
        int written = 0;
        try {
            Class<?> identityUtil = XposedHelpers.findClass("gtnm", loader);
            for (Object item : (Collection<?>) value) {
                if (written++ > 0) {
                    out.append(',');
                }
                Object identity = XposedHelpers.callStaticMethod(
                        identityUtil, "d", readField(item, "c"));
                out.append(byteStringHash(identity));
            }
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
        return out.append(']').toString();
    }

    private static int byteStringHash(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            Object bytes = XposedHelpers.callMethod(value, "N");
            return bytes instanceof byte[] ? Arrays.hashCode((byte[]) bytes) : value.hashCode();
        } catch (Throwable ignored) {
            Object bytes = readField(value, "a");
            return bytes instanceof byte[] ? Arrays.hashCode((byte[]) bytes) : value.hashCode();
        }
    }

    private static String describeOwnerBatch(Object batch) {
        StringBuilder out = new StringBuilder(describeNamedFields(batch, "b", "e", "f"));
        Object groupsValue = readField(batch, "c");
        if (!(groupsValue instanceof Collection)) {
            return out.append(" groups=unavailable").toString();
        }
        Collection<?> groups = (Collection<?>) groupsValue;
        out.append(" groups=").append(groups.size()).append('[');
        int groupIndex = 0;
        for (Object group : groups) {
            if (groupIndex > 0) {
                out.append(", ");
            }
            if (groupIndex++ >= 3) {
                out.append('…');
                break;
            }
            Object sightingsValue = readField(group, "c");
            Object identity = readField(group, "e");
            out.append("{flags=")
                    .append(safe(readField(group, "b")))
                    .append(", sightings=")
                    .append(collectionSize(sightingsValue))
                    .append(", time=")
                    .append(describePrimitiveFields(readField(group, "d")))
                    .append(", identity=")
                    .append(describeOneOf(identity));
            if (sightingsValue instanceof Collection && !((Collection<?>) sightingsValue).isEmpty()) {
                Object sighting = ((Collection<?>) sightingsValue).iterator().next();
                out.append(", first={")
                        .append(describeNamedFields(sighting, "b", "d", "e"))
                        .append(", identifier=")
                        .append(describeOneOf(readField(sighting, "c")))
                        .append('}');
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static String describeOneOf(Object value) {
        if (value == null) {
            return "null";
        }
        Object payload = readField(value, "c");
        return value.getClass().getSimpleName()
                + "{case="
                + safe(readField(value, "b"))
                + ", payload="
                + (payload == null ? "null" : payload.getClass().getSimpleName())
                + ", scalars="
                + describePrimitiveFields(payload)
                + ("jgvt".equals(payload == null ? "" : payload.getClass().getSimpleName())
                        ? ", byteHash=" + byteStringHash(payload) : "")
                + "}";
    }

    private static Object readField(Object value, String name) {
        if (value == null) {
            return null;
        }
        try {
            Field field = value.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(value);
        } catch (Throwable ignored) {
            return null;
        }
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
