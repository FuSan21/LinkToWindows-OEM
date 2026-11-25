/*
 * SPDX-License-Identifier: MIT
 *
 * Client side of the Link to Windows OEM contract, shipped as the shared library
 * "com.microsoft.device-private" (which com.microsoft.deviceintegrationservice already declares via
 * <uses-library android:required="false">). Behaviour mirrors OnePlus 12 / OxygenOS 15
 * oplus-framework.jar android.app.CrossDeviceManager (see docs/OEM_INTEGRATION_PLAN.md section 8c).
 *
 * This class is loaded by the APP's class loader, never the boot class path, so none of it is subject
 * to hidden-API enforcement. The framework's SystemServiceRegistry instantiates it reflectively from
 * the caller's ClassLoader for getSystemService("cross_device_service").
 */
package android.app;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.InputEvent;
import android.view.Surface;

import java.util.List;

public class CrossDeviceManager {
    private static final String TAG = "CrossDeviceManager";

    public static final String SERVICE_NAME = "cross_device_service";

    public static final int APP_FEATURE_TYPE_UNKNOWN = 0;
    public static final int APP_FEATURE_TYPE_PHONE_SCREEN_ONLY = 1;
    public static final int APP_FEATURE_TYPE_MULTIPLE_APPS = 2;

    public static final int ASSET_CATEGORY_UNKNOWN = 0;
    public static final int ASSET_CATEGORY_PHONE = 1;
    public static final int ASSET_CATEGORY_TABLET = 2;
    public static final int ASSET_CATEGORY_FOLD_VERTICAL = 3;
    public static final int ASSET_CATEGORY_FOLD_HORIZONTAL = 4;

    public static final String FEATURE_BASIC = "feature_basic";
    public static final String FEATURE_REMOTE_APPS = "feature_remote_apps";
    public static final String FEATURE_OEM_RFCOMM = "feature_oem_rfcomm";
    public static final String FEATURE_RECENT_APPS = "feature_recent_apps";
    public static final String FEATURE_INSTANT_HOTSPOT = "feature_instant_hotspot";
    public static final String FEATURE_AUDIO_STREAM = "feature_audio_stream";
    public static final String FEATURE_CALLING = "feature_calling";
    public static final String FEATURE_MESSAGING = "feature_messaging";
    public static final String FEATURE_PROXIMITY = "feature_proximity";

    /** Remote Task ("Apps", milestone 4): LtwRemoteTaskManager in system_server. */
    private static final boolean REMOTE_APPS_IMPLEMENTED = true;

    // Bundle keys read by deviceintegrationservice (thermal/e.java, thermal/b.java).
    private static final String KEY_DEVICE_TEMPERATURE = "deviceintegration:device.temperature";
    private static final String KEY_OVERHEAT_SUSPEND = "deviceintegration:overheat.suspend.threshold";
    private static final String KEY_OVERHEAT_RESUME = "deviceintegration:overheat.resume.threshold";
    private static final float OVERHEAT_THRESHOLD = 45.0f;        // RemoteTaskConstants on OnePlus
    private static final float RESUME_OVERHEAT_THRESHOLD = 40.0f;

    private static final int CROSS_DEVICE_VERSION = 1;
    private static final String ALLOWED_CALLER = "com.microsoft.deviceintegrationservice";
    private static final String PERMISSION_MANAGE_CROSS_DEVICE =
            "deviceintegration.permission.MANAGE_CROSS_DEVICE";

    private static final Object sLock = new Object();
    private static ICrossDeviceService sService;

    /** Instantiated reflectively by SystemServiceRegistry (no-arg). */
    public CrossDeviceManager() {
    }

    /** Also accepts a Context, in case a caller constructs it directly. */
    public CrossDeviceManager(Context context) {
    }

    public static ICrossDeviceService getService() {
        synchronized (sLock) {
            if (sService == null || !sService.asBinder().isBinderAlive()) {
                IBinder b = ServiceManager.getService(SERVICE_NAME);
                sService = b != null ? ICrossDeviceService.Stub.asInterface(b) : null;
            }
            return sService;
        }
    }

    private static boolean sMissingLogged;

    /**
     * The service, or null if it is not there (yet): the root module registers it only from the
     * boot after LtwEmu is enabled, and it can be absent if LtwEmu fails to load. Callers then
     * behave like a device without the feature instead of crashing deviceintegrationservice
     * (which calls getThermalStatus while starting up).
     */
    private static ICrossDeviceService service() {
        ICrossDeviceService s = getService();
        if (s == null && !sMissingLogged) {
            sMissingLogged = true;
            Log.w(TAG, SERVICE_NAME + " is not available; cross-device calls do nothing");
        }
        return s;
    }

    // ---------------------------------------------------------------- static, no binder needed

    public static boolean isFeatureSupported(String featureName) {
        if (featureName == null) return false;
        switch (featureName) {
            case FEATURE_BASIC:
                return true; // OemService.onBind throws if this is false
            case FEATURE_REMOTE_APPS:
                return REMOTE_APPS_IMPLEMENTED;
            case FEATURE_RECENT_APPS:
            case FEATURE_INSTANT_HOTSPOT:
            case FEATURE_AUDIO_STREAM:
            case FEATURE_CALLING:
            case FEATURE_MESSAGING:
            case FEATURE_OEM_RFCOMM:  // Bluetooth side channel (LtwRfcommManager), as OnePlus
                return true;
            case FEATURE_PROXIMITY:   // OnePlus: false.
            default:
                return false;
        }
    }

    public static boolean isThreeStageVolumeDevice() {
        return false; // no alert slider on Pixel
    }

    public static int getAssetCategory() {
        return ASSET_CATEGORY_PHONE;
    }

    public static int getMaxFps(int appsFeatureType) {
        switch (appsFeatureType) {
            case APP_FEATURE_TYPE_PHONE_SCREEN_ONLY:
            case APP_FEATURE_TYPE_MULTIPLE_APPS:
                return 30;
            default:
                return 15;
        }
    }

    public static int getCrossDeviceVersion() {
        return CROSS_DEVICE_VERSION;
    }

    /** Same rule system_server enforces in CrossDeviceService.onTransact. */
    public static boolean isCallerAllowed(Context context) {
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        if (app.checkCallingOrSelfPermission(PERMISSION_MANAGE_CROSS_DEVICE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        String pkg = app.getPackageManager().getNameForUid(Binder.getCallingUid());
        return ALLOWED_CALLER.equals(pkg);
    }

    public static void notifyConnectionStateChange(boolean isConnected) {
        // OnePlus only feeds OPPO telemetry here.
        Log.d(TAG, "Connection state changed: " + (isConnected ? "connected" : "disconnected"));
    }

    // ---------------------------------------------------------------- binder-backed

    public void injectInputEvent(InputEvent event, int mode) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.injectInputEvent(event, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public String getTopRunningPackageName() {
        final ICrossDeviceService s = service();
        if (s == null) return null;
        try {
            return s.getTopRunningPackageName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void wakeUp(long time) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.wakeUp(time);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void addRemoteTaskCallback(IRemoteTaskCallback callback) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.addRemoteTaskCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void removeRemoteTaskCallback(IRemoteTaskCallback callback) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.removeRemoteTaskCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void updateBackgroundActivityList(List<String> activityList) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.updateBackgroundActivityList(activityList);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void updateSurface(String uuid, Surface surface) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.updateSurface(uuid, surface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setDisplayInfo(int width, int height, int densityDpi) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.updateDisplayInfo(width, height, densityDpi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getThermalStatus() {
        final ICrossDeviceService s = service();
        if (s == null) return 0;
        try {
            return s.getThermalStatus();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean isResumableFromOverheat() {
        final ICrossDeviceService s = service();
        if (s == null) return true;
        try {
            return s.isResumableFromOverheat();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Newer DIS API (not on OnePlus A15). Missing key => DIS treats temperature as unknown. */
    public Bundle getDeviceTemperature() {
        Bundle b = new Bundle();
        final ICrossDeviceService s = service();
        if (s == null) return b; // temperature unknown
        try {
            float t = s.getDeviceTemperature();
            if (!Float.isNaN(t)) {
                b.putFloat(KEY_DEVICE_TEMPERATURE, t);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return b;
    }

    public Bundle getOverheatThreshold() {
        Bundle b = new Bundle();
        b.putFloat(KEY_OVERHEAT_SUSPEND, OVERHEAT_THRESHOLD);
        return b;
    }

    public Bundle getResumeOverheatThreshold() {
        Bundle b = new Bundle();
        b.putFloat(KEY_OVERHEAT_RESUME, RESUME_OVERHEAT_THRESHOLD);
        return b;
    }

    public IRfcommOemManager getRfcommOemManager() {
        final ICrossDeviceService s = service();
        if (s == null) return null;
        try {
            return s.getRfcommOemManager();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setPermissionGranted(int uid) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.setPermissionGranted(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean getPermissionGranted(int uid) {
        final ICrossDeviceService s = service();
        if (s == null) return false;
        try {
            return s.getPermissionGranted(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setSecurityToken(String securityToken) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.setSecurityToken(securityToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void registerTaskChangeListener(ITaskChangeListener listener) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.registerTaskChangeListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void unregisterTaskChangeListener(ITaskChangeListener listener) {
        final ICrossDeviceService s = service();
        if (s == null) return;
        try {
            s.unregisterTaskChangeListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public IProximityService getProximityService() {
        final ICrossDeviceService s = service();
        if (s == null) return null;
        try {
            return s.getProximityService();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean isInstantHotspotCapable() {
        final ICrossDeviceService s = service();
        if (s == null) return false;
        try {
            return s.isInstantHotspotCapable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
