package com.leowood.gmsfastpairdiagnostics;

import android.location.Location;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Corrects Find Hub's WGS-84 device coordinates at its map UI boundary.
 *
 * <p>The hook is deliberately limited to Find Hub's marker, device-camera,
 * and Google Maps location display pipelines. It does not alter GMS, stored
 * locations, network requests, or coordinates outside the GCJ-02 coverage
 * guard.</p>
 */
final class FindHubMapHook {
    private static final String TAG = "[FindHubMapFix] ";
    private static final double PI = Math.PI;
    private static final double EARTH_SEMI_MAJOR_AXIS = 6378245.0;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323;
    // Simplified Natural Earth mainland boundary (longitude, latitude).
    private static final double[] MAINLAND_BOUNDARY = {
            78.9177, 33.3863, 78.9762, 34.3092, 78.2960, 34.6247, 78.0443, 35.4916,
            76.1660, 35.8062, 75.8498, 36.6447, 74.3826, 37.1266, 75.1641, 37.4006,
            74.8358, 38.4552, 73.8164, 38.5866, 73.6326, 39.4483, 74.8354, 40.5116,
            76.3135, 40.3433, 76.8610, 41.0132, 78.0750, 41.0395, 80.2312, 42.0337,
            80.1438, 42.6448, 80.7934, 43.1495, 80.4923, 44.7280, 79.8582, 44.9037,
            82.5397, 45.1237, 82.2915, 45.5332, 83.0215, 47.2059, 85.4986, 47.0518,
            85.7187, 48.3588, 86.5651, 48.5273, 86.8595, 49.1053, 87.8163, 49.1658,
            87.9830, 48.5523, 90.3448, 47.6586, 91.0475, 46.5664, 90.8732, 45.1862,
            95.3977, 44.2805, 96.3578, 42.7245, 101.5249, 42.5375, 104.9738, 41.5861,
            106.7678, 42.2866, 110.4067, 42.7686, 111.9334, 43.6966, 111.3971, 44.3874,
            111.9583, 45.0845, 113.6048, 44.7397, 117.3938, 46.5714, 119.8544, 46.6597,
            118.5423, 47.9662, 115.9145, 47.6839, 115.5142, 48.1316, 116.6843, 49.8233,
            117.8369, 49.5090, 119.3162, 50.0927, 119.1376, 50.3925, 120.7792, 52.1176,
            120.0330, 52.7607, 120.8743, 53.2802, 123.6143, 53.5633, 125.6214, 53.0621,
            126.5560, 52.1306, 127.5387, 49.7899, 130.6743, 48.8708, 131.0234, 47.6823,
            134.7187, 48.2634, 134.7726, 47.7107, 133.0988, 45.1078, 131.8531, 45.3376,
            130.9334, 44.8417, 131.2809, 43.3802, 130.5308, 42.5305, 129.8798, 42.9960,
            129.7033, 42.4424, 128.0346, 41.9937, 128.1461, 41.3763, 126.6795, 41.7360,
            124.1372, 39.8424, 121.1795, 38.7209, 121.9430, 39.3994, 121.2234, 39.5286,
            122.3019, 40.5023, 121.8679, 40.9958, 120.9819, 40.8268, 118.9260, 39.1306,
            117.7154, 39.1114, 117.7161, 38.3785, 118.9500, 38.0973, 119.1455, 37.1789,
            120.7380, 37.8340, 122.6882, 37.4098, 122.5074, 36.8980, 120.0972, 36.2264,
            120.2988, 35.9731, 119.1929, 35.0004, 120.2571, 34.3118, 120.8303, 32.6977,
            121.9221, 31.7544, 119.6129, 32.3546, 121.9508, 30.9821, 120.1471, 30.1989,
            121.2541, 30.3484, 122.1291, 29.9038, 121.4434, 29.5242, 121.9712, 29.5933,
            121.4085, 29.1611, 121.6214, 28.7359, 121.1423, 28.8459, 121.6563, 28.3393,
            120.5596, 28.1128, 120.8684, 27.8826, 120.1344, 26.6461, 119.5521, 26.7491,
            119.9500, 26.3649, 119.0935, 26.1458, 119.6954, 26.0026, 119.6546, 25.3581,
            119.3052, 25.6046, 118.6205, 24.5455, 117.7913, 24.4675, 118.1265, 24.2620,
            116.5232, 23.4185, 116.4947, 22.9394, 113.8903, 22.4527, 113.5288, 23.0108,
            113.8301, 23.1175, 113.4175, 23.0970, 113.4837, 22.1551, 113.1653, 22.5756,
            113.4020, 22.1796, 112.9116, 21.8563, 110.3585, 21.4357, 110.2803, 20.2530,
            109.9172, 20.2393, 109.5945, 21.7464, 109.1387, 21.4016, 108.5733, 21.9518,
            108.4710, 21.5620, 107.3482, 21.5994, 106.6531, 21.9689, 106.7898, 22.7972,
            105.3122, 23.3658, 103.9595, 22.5071, 101.6891, 22.4789, 101.7560, 21.1433,
            101.2443, 21.1929, 101.0828, 21.7667, 100.1625, 21.4364, 99.9424, 22.0455,
            99.1445, 22.1535, 99.5380, 22.9264, 98.8590, 23.1794, 98.8657, 24.1457,
            97.5165, 23.9428, 97.8010, 25.2376, 98.6902, 25.8656, 98.6793, 27.5773,
            97.6705, 28.5113, 96.3019, 28.4207, 96.5926, 28.7579, 96.1420, 29.3685,
            95.3674, 29.0365, 94.5999, 29.3166, 91.9522, 27.7248, 90.2256, 28.3584,
            88.9719, 27.3124, 88.6105, 28.1058, 85.9803, 27.8852, 82.0888, 30.3301,
            81.0978, 30.0169, 79.1314, 31.4384, 78.7451, 31.3081, 78.3848, 32.5475,
            79.6207, 32.7287, 78.9177, 33.3863
    };
    private static final double[] HAINAN_BOUNDARY = {
            108.62, 19.28, 109.16, 18.24, 110.07, 18.16, 110.70, 18.66,
            111.05, 19.64, 110.58, 20.15, 109.45, 20.16, 108.62, 19.28
    };

    /**
     * A location proto may be passed through the UI pipeline more than once.
     * Identity tracking prevents an accidental second nonlinear conversion.
     */
    private static final Map<Object, Coordinate> ORIGINAL_COORDINATES =
            new WeakHashMap<>();
    /**
     * Find Hub's recenter controls write fresh copies of selected-device or
     * blue-dot coordinates into a separate camera-state pipeline. Remember
     * known raw/corrected pairs so arbitrary camera positions created by user
     * panning remain untouched.
     */
    private static final Map<String, Coordinate> MARKER_CAMERA_TARGETS =
            new HashMap<>();
    private static final Set<String> CORRECTED_CAMERA_TARGETS =
            new HashSet<>();
    /**
     * Camera controllers currently following Find Hub's built-in user
     * location. Weak keys avoid retaining a controller after its screen is
     * destroyed.
     */
    private static final Map<Object, Boolean> USER_LOCATION_CAMERA_STATES =
            new WeakHashMap<>();
    private static final ThreadLocal<Boolean> READING_LOCATION =
            new ThreadLocal<>();

    private FindHubMapHook() {
    }

    static void install(ClassLoader loader, String processName) {
        int markerHooks = 0;
        markerHooks += hookMarkerPipeline(loader, "hwi", "aM");
        markerHooks += hookMarkerPipeline(loader, "hfo", "aN");
        int cameraModeHooks = hookCameraModePipeline(loader, "odd", "j");
        int cameraHooks = hookDeviceCameraPipeline(loader, "odd", "n");
        log("loaded process=" + processName
                + " markerHooks=" + markerHooks
                + " cameraModeHooks=" + cameraModeHooks
                + " cameraHooks=" + cameraHooks);
        hookMapLocationLayer();
    }

    private static int hookMarkerPipeline(
            ClassLoader loader, String className, String methodName) {
        Class<?> fragment = XposedHelpers.findClassIfExists(className, loader);
        if (fragment == null) {
            log("marker pipeline class not found " + className);
            return 0;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                fragment,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1
                                || param.args[0] == null) {
                            return;
                        }
                        convertMarkerCollection(param.args[0]);
                    }
                });
        log("marker pipeline " + className + "#" + methodName
                + " overloads=" + hooks.size());
        return hooks.size();
    }

    private static int hookCameraModePipeline(
            ClassLoader loader, String className, String methodName) {
        Class<?> cameraState = XposedHelpers.findClassIfExists(
                className, loader);
        if (cameraState == null) {
            log("camera mode class not found " + className);
            return 0;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                cameraState,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1
                                || param.args[0] == null) {
                            return;
                        }
                        boolean followsUserLocation = "USER_LOCATION".equals(
                                String.valueOf(param.args[0]));
                        synchronized (USER_LOCATION_CAMERA_STATES) {
                            USER_LOCATION_CAMERA_STATES.put(
                                    param.thisObject, followsUserLocation);
                        }
                    }
                });
        log("camera mode pipeline " + className + "#" + methodName
                + " overloads=" + hooks.size());
        return hooks.size();
    }

    private static int hookDeviceCameraPipeline(
            ClassLoader loader, String className, String methodName) {
        Class<?> cameraState = XposedHelpers.findClassIfExists(
                className, loader);
        if (cameraState == null) {
            log("camera pipeline class not found " + className);
            return 0;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                cameraState,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 3
                                || !(param.args[0] instanceof Number)
                                || !(param.args[1] instanceof Number)
                                || !(param.args[2] instanceof Number)) {
                            return;
                        }
                        double latitude =
                                ((Number) param.args[0]).doubleValue();
                        double longitude =
                                ((Number) param.args[1]).doubleValue();
                        Coordinate result = correctedMarkerTarget(
                                latitude, longitude);
                        if (result == null
                                && isFollowingUserLocation(param.thisObject)
                                && !isCorrectedCameraTarget(
                                latitude, longitude)
                                && isGcj02Region(latitude, longitude)) {
                            // Continuous location updates can reach the camera
                            // before Google Maps reads the same Location for
                            // its blue dot. Correct them while, and only while,
                            // Find Hub is explicitly following USER_LOCATION.
                            result = wgs84ToGcj02(latitude, longitude);
                            rememberCameraTarget(
                                    latitude, longitude, result);
                        }
                        if (result == null) {
                            return;
                        }
                        param.args[0] = result.latitude;
                        param.args[1] = result.longitude;
                        log("camera " + format(latitude) + ","
                                + format(longitude) + " -> "
                                + format(result.latitude) + ","
                                + format(result.longitude));
                    }
                });
        log("camera pipeline " + className + "#" + methodName
                + " overloads=" + hooks.size());
        return hooks.size();
    }

    /**
     * Find Hub also enables GoogleMap's built-in blue "my location" dot. That
     * path bypasses the MarkerInfo collection, so its Location getters need
     * the same UI-only correction or a nearby tag appears as a second point.
     */
    private static void hookMapLocationLayer() {
        hookLocationGetter("getLatitude", true);
        hookLocationGetter("getLongitude", false);
    }

    private static void hookLocationGetter(String methodName, boolean latitude) {
        XposedBridge.hookAllMethods(
                Location.class,
                methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()
                                || !(param.getResult() instanceof Number)
                                || Boolean.TRUE.equals(READING_LOCATION.get())
                                || !isGoogleMapLocationCall()) {
                            return;
                        }
                        try {
                            READING_LOCATION.set(Boolean.TRUE);
                            Location location = (Location) param.thisObject;
                            double sourceLatitude = latitude
                                    ? ((Number) param.getResult()).doubleValue()
                                    : location.getLatitude();
                            double sourceLongitude = latitude
                                    ? location.getLongitude()
                                    : ((Number) param.getResult()).doubleValue();
                            if (!isGcj02Region(
                                    sourceLatitude, sourceLongitude)) {
                                return;
                            }
                            Coordinate result = wgs84ToGcj02(
                                    sourceLatitude, sourceLongitude);
                            // The People tab's crosshair recenters on the
                            // built-in blue location dot rather than a Find
                            // Hub marker. Register the same raw/corrected pair
                            // so its camera target follows the corrected dot.
                            rememberCameraTarget(
                                    sourceLatitude, sourceLongitude, result);
                            param.setResult(latitude
                                    ? result.latitude : result.longitude);
                        } finally {
                            READING_LOCATION.remove();
                        }
                    }
                });
    }

    private static boolean isGoogleMapLocationCall() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("com.google.maps.api.android.lib6.")
                    || className.startsWith("com.google.android.gms.maps.")
                    || className.startsWith("com.google.android.libraries.maps.")) {
                return true;
            }
        }
        return false;
    }

    private static void convertMarkerCollection(Object collection) {
        int converted = 0;
        try {
            Iterator<?> iterator = iteratorOf(collection);
            while (iterator.hasNext()) {
                Object marker = iterator.next();
                Object locationWithAccuracy = getField(marker, "b");
                Object point = getField(locationWithAccuracy, "c");
                if (point == null) {
                    continue;
                }

                Coordinate original = getOriginal(point);
                double latitude = original != null
                        ? original.latitude : getDouble(point, "b");
                double longitude = original != null
                        ? original.longitude : getDouble(point, "c");
                if (!isGcj02Region(latitude, longitude)) {
                    continue;
                }

                Coordinate result = wgs84ToGcj02(latitude, longitude);
                if (original == null) {
                    rememberOriginal(
                            point, new Coordinate(latitude, longitude));
                }
                rememberCameraTarget(latitude, longitude, result);
                setDouble(point, "b", result.latitude);
                setDouble(point, "c", result.longitude);
                converted++;
                log("marker " + format(latitude) + "," + format(longitude)
                        + " -> " + format(result.latitude) + ","
                        + format(result.longitude));
            }
        } catch (Throwable error) {
            log("marker conversion failed: " + error);
        }
        if (converted > 0) {
            log("converted markers=" + converted);
        }
    }

    private static Iterator<?> iteratorOf(Object collection) throws Exception {
        if (collection instanceof Iterable<?>) {
            return ((Iterable<?>) collection).iterator();
        }
        Method iterator = collection.getClass().getMethod("iterator");
        return (Iterator<?>) iterator.invoke(collection);
    }

    private static Object getField(Object target, String name) throws Exception {
        if (target == null) {
            return null;
        }
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static double getDouble(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(target);
    }

    private static void setDouble(Object target, String name, double value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(target, value);
    }

    private static Coordinate getOriginal(Object point) {
        synchronized (ORIGINAL_COORDINATES) {
            return ORIGINAL_COORDINATES.get(point);
        }
    }

    private static void rememberOriginal(Object point, Coordinate original) {
        synchronized (ORIGINAL_COORDINATES) {
            ORIGINAL_COORDINATES.put(point, original);
        }
    }

    private static void rememberCameraTarget(
            double latitude, double longitude, Coordinate corrected) {
        synchronized (MARKER_CAMERA_TARGETS) {
            if (MARKER_CAMERA_TARGETS.size() >= 256) {
                MARKER_CAMERA_TARGETS.clear();
                CORRECTED_CAMERA_TARGETS.clear();
            }
            MARKER_CAMERA_TARGETS.put(
                    coordinateKey(latitude, longitude), corrected);
            CORRECTED_CAMERA_TARGETS.add(coordinateKey(
                    corrected.latitude, corrected.longitude));
        }
    }

    private static Coordinate correctedMarkerTarget(
            double latitude, double longitude) {
        String key = coordinateKey(latitude, longitude);
        synchronized (MARKER_CAMERA_TARGETS) {
            if (CORRECTED_CAMERA_TARGETS.contains(key)) {
                return null;
            }
        return MARKER_CAMERA_TARGETS.get(key);
        }
    }

    private static boolean isCorrectedCameraTarget(
            double latitude, double longitude) {
        synchronized (MARKER_CAMERA_TARGETS) {
            return CORRECTED_CAMERA_TARGETS.contains(
                    coordinateKey(latitude, longitude));
        }
    }

    private static boolean isFollowingUserLocation(Object cameraState) {
        synchronized (USER_LOCATION_CAMERA_STATES) {
            return Boolean.TRUE.equals(
                    USER_LOCATION_CAMERA_STATES.get(cameraState));
        }
    }

    private static String coordinateKey(double latitude, double longitude) {
        return Math.round(latitude * 10000000.0) + ":"
                + Math.round(longitude * 10000000.0);
    }

    /**
     * The conventional GCJ-02 coverage check, with explicit HK/Macau/Taiwan
     * exclusions. Coordinates outside it are returned completely unchanged.
     */
    private static boolean isGcj02Region(double latitude, double longitude) {
        if (longitude < 72.004 || longitude > 137.8347
                || latitude < 0.8293 || latitude > 55.8271) {
            return false;
        }
        // Hong Kong
        if (latitude >= 22.08 && latitude <= 22.58
                && longitude >= 113.82 && longitude <= 114.52) {
            return false;
        }
        // Macau
        if (latitude >= 22.06 && latitude <= 22.23
                && longitude >= 113.52 && longitude <= 113.64) {
            return false;
        }
        // Taiwan
        if (latitude >= 21.80 && latitude <= 25.45
                && longitude >= 119.30 && longitude <= 122.10) {
            return false;
        }
        return insideBoundary(latitude, longitude, MAINLAND_BOUNDARY)
                || insideBoundary(latitude, longitude, HAINAN_BOUNDARY);
    }

    private static boolean insideBoundary(
            double latitude, double longitude, double[] boundary) {
        boolean inside = false;
        int pointCount = boundary.length / 2;
        for (int i = 0, j = pointCount - 1; i < pointCount; j = i++) {
            double longitudeI = boundary[i * 2];
            double latitudeI = boundary[i * 2 + 1];
            double longitudeJ = boundary[j * 2];
            double latitudeJ = boundary[j * 2 + 1];
            boolean crosses = (latitudeI > latitude) != (latitudeJ > latitude)
                    && longitude < (longitudeJ - longitudeI)
                    * (latitude - latitudeI) / (latitudeJ - latitudeI)
                    + longitudeI;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static Coordinate wgs84ToGcj02(double latitude, double longitude) {
        double latitudeDelta = transformLatitude(
                longitude - 105.0, latitude - 35.0);
        double longitudeDelta = transformLongitude(
                longitude - 105.0, latitude - 35.0);
        double latitudeRadians = latitude / 180.0 * PI;
        double sinLatitude = Math.sin(latitudeRadians);
        double magic = 1.0 - ECCENTRICITY_SQUARED
                * sinLatitude * sinLatitude;
        double squareRootMagic = Math.sqrt(magic);
        latitudeDelta = latitudeDelta * 180.0
                / ((EARTH_SEMI_MAJOR_AXIS
                * (1.0 - ECCENTRICITY_SQUARED))
                / (magic * squareRootMagic) * PI);
        longitudeDelta = longitudeDelta * 180.0
                / (EARTH_SEMI_MAJOR_AXIS / squareRootMagic
                * Math.cos(latitudeRadians) * PI);
        return new Coordinate(
                latitude + latitudeDelta,
                longitude + longitudeDelta);
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0 + 2.0 * x + 3.0 * y
                + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * PI)
                + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(y * PI)
                + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        result += (160.0 * Math.sin(y / 12.0 * PI)
                + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0 + x + 2.0 * y
                + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * PI)
                + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(x * PI)
                + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        result += (150.0 * Math.sin(x / 12.0 * PI)
                + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return result;
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.7f", value);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }

    private static final class Coordinate {
        final double latitude;
        final double longitude;

        Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
