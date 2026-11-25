/*
 * SPDX-License-Identifier: MIT
 *
 * Link to Windows OEM integration: the "cross_device_service" binder.
 *
 * Port of OnePlus 12 / OxygenOS 15 oplus-services.jar com.android.server.wm.CrossDeviceService
 * (+ TaskChangeNotifier/TaskChangeWatcher, CrossDeviceDisplayInfo). See
 * docs/OEM_INTEGRATION_NOTES.md section 8c in the LinkToWindows-OEM repo.
 *
 * The android.app.* AIDL used here comes from vendor/ltw (static lib ltw-crossdevice-aidl-server);
 * the matching client copy lives in the com.microsoft.device-private shared library.
 *
 * Remote Task (app streaming, milestone 4) is implemented by {@link LtwRemoteTaskManager} through
 * {@link RemoteTaskHost}.
 *
 * This file is the single source for BOTH the frameworks/base patch (0001) and the rooted-stock
 * emulation module (module/, LSPosed in system_server). It therefore only uses framework.jar
 * APIs (IActivityTaskManager, not ActivityTaskManagerService). After editing, regenerate patch 0001.
 */
package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.ICrossDeviceService;
import android.app.IProximityService;
import android.app.IRemoteTaskCallback;
import android.app.IRfcommOemManager;
import android.app.IServiceRemoteTaskInstance;
import android.app.ITaskChangeListener;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.input.InputManagerGlobal;
import android.net.TetheringManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.InputEvent;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class CrossDeviceService extends ICrossDeviceService.Stub {
    private static final String TAG = "CrossDeviceService";

    static final String PERMISSION_MANAGE_CROSS_DEVICE =
            "deviceintegration.permission.MANAGE_CROSS_DEVICE";
    /** Only caller OnePlus allows (CrossDeviceManager.CROSS_DEVICE_ALLOW_LIST). */
    static final String ALLOWED_CALLER = "com.microsoft.deviceintegrationservice";

    // RemoteTaskConstants
    static final int DEVICE_AVAILABILITY_STATE_FREE = 8;
    static final float OVERHEAT_THRESHOLD = 45.0f;
    static final float RESUME_OVERHEAT_THRESHOLD = 40.0f;
    /** OnePlus averages Temperature.TYPE_BATTERY (2) sensors. */
    private static final int THERMAL_SENSOR_TYPE = Temperature.TYPE_BATTERY;
    /** PowerManager.WAKE_REASON_APPLICATION (@hide); OnePlus passes 2. */
    private static final int WAKE_REASON_APPLICATION = 2;

    /** Implemented by the Remote Task WM patch (milestone 4). */
    interface RemoteTaskHost {
        void updateSurface(String uuid, Surface surface);
        /** All remote-task clients are gone: close every remote task (OnePlus recycleAll). */
        void onAllClientsDied();
    }

    private final Context mContext;
    private final Object mLock = new Object();

    private final RemoteCallbackList<IRemoteTaskCallback> mRemoteCallbacks =
            new RemoteCallbackList<>() {
                @Override
                public void onCallbackDied(IRemoteTaskCallback callback) {
                    Slog.i(TAG, "remote task callback died");
                    handleClientDied();
                }
            };

    private final Set<String> mBackgroundActivityAllowlist = new CopyOnWriteArraySet<>();
    private final Set<Integer> mPermissionGrantedUids = new CopyOnWriteArraySet<>();
    private final TaskChangeNotifier mTaskChangeNotifier;
    private final LtwRfcommManager mRfcommManager;

    private volatile String mSecurityToken;
    private volatile RemoteTaskHost mRemoteTaskHost;
    private IThermalService mThermalService;

    // CrossDeviceDisplayInfo
    private boolean mDisplayInfoSet;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mDisplayDensityDpi;

    public CrossDeviceService(Context context) {
        mContext = context;
        mTaskChangeNotifier = new TaskChangeNotifier();
        mRfcommManager = new LtwRfcommManager(context);
        // Link to Windows "Apps" (milestone 4); hooked from ActivityTaskManagerService.
        mRemoteTaskHost = new LtwRemoteTaskManager(context, this);
    }

    // ------------------------------------------------------------------ caller gate

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        // Let the binder framework answer INTERFACE/DUMP/etc. transactions itself.
        if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
            enforceCallerAllowed();
        }
        return super.onTransact(code, data, reply, flags);
    }

    private void enforceCallerAllowed() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID) return;
        if (mContext.checkCallingPermission(PERMISSION_MANAGE_CROSS_DEVICE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller uid " + uid + " lacks "
                    + PERMISSION_MANAGE_CROSS_DEVICE);
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final String[] pkgs = mContext.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    if (ALLOWED_CALLER.equals(p)) return;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new SecurityException("Caller uid " + uid + " is not allowed");
    }

    // ------------------------------------------------------------------ ICrossDeviceService

    @Override
    public void injectInputEvent(InputEvent event, int mode) {
        final long token = Binder.clearCallingIdentity();
        try {
            InputManagerGlobal.getInstance().injectInputEvent(event, mode);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String getTopRunningPackageName() {
        final long token = Binder.clearCallingIdentity();
        try {
            final List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getService()
                    .getTasks(1, false /* filterOnlyVisibleRecents */,
                            false /* keepIntentExtra */, Display.INVALID_DISPLAY);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
                return "";
            }
            return tasks.get(0).topActivity.getPackageName();
        } catch (RemoteException e) {
            return ""; // in-process call; cannot really happen
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void wakeUp(long time) {
        final long token = Binder.clearCallingIdentity();
        try {
            // == PowerManager.wakeUp(time, WAKE_REASON_APPLICATION, details), which is @hide.
            IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE))
                    .wakeUp(time, WAKE_REASON_APPLICATION,
                            "android.server.wm.CrossDeviceService:wakeUp",
                            mContext.getOpPackageName());
        } catch (RemoteException e) {
            Slog.w(TAG, "wakeUp failed", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void addRemoteTaskCallback(IRemoteTaskCallback callback) throws RemoteException {
        Slog.i(TAG, "addRemoteTaskCallback");
        if (callback == null) return;
        mRemoteCallbacks.register(callback);
        callback.notifyDeviceAvailabilityStateChanged(DEVICE_AVAILABILITY_STATE_FREE);
    }

    @Override
    public void removeRemoteTaskCallback(IRemoteTaskCallback callback) {
        Slog.i(TAG, "removeRemoteTaskCallback");
        if (callback != null) mRemoteCallbacks.unregister(callback);
        handleClientDied();
    }

    @Override
    public void updateBackgroundActivityList(List<String> activityList) {
        if (activityList != null) mBackgroundActivityAllowlist.addAll(activityList);
    }

    @Override
    public void updateSurface(String uuid, Surface surface) {
        final RemoteTaskHost host = mRemoteTaskHost;
        if (host == null) {
            Slog.w(TAG, "updateSurface(" + uuid + ") ignored: Remote Task not implemented");
            return;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            host.updateSurface(uuid, surface);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void updateDisplayInfo(int width, int height, int densityDpi) {
        synchronized (mLock) {
            mDisplayWidth = width;
            mDisplayHeight = height;
            mDisplayDensityDpi = densityDpi;
            mDisplayInfoSet = true;
        }
    }

    @Override
    public int getThermalStatus() {
        final float t = getDeviceTemperature();
        return (!Float.isNaN(t) && t >= OVERHEAT_THRESHOLD) ? 1 : 0;
    }

    @Override
    public boolean isResumableFromOverheat() {
        final float t = getDeviceTemperature();
        return !Float.isNaN(t) && t <= RESUME_OVERHEAT_THRESHOLD;
    }

    @Override
    public float getDeviceTemperature() {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (mThermalService == null) {
                    mThermalService = IThermalService.Stub.asInterface(
                            ServiceManager.getService("thermalservice"));
                }
            }
            if (mThermalService == null) return Float.NaN;
            final Temperature[] temps =
                    mThermalService.getCurrentTemperaturesWithType(THERMAL_SENSOR_TYPE);
            if (temps == null || temps.length == 0) return Float.NaN;
            float sum = 0f;
            for (Temperature t : temps) sum += t.getValue();
            return sum / temps.length;
        } catch (RemoteException e) {
            Slog.w(TAG, "getDeviceTemperature failed", e);
            return Float.NaN;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public IRfcommOemManager getRfcommOemManager() {
        return mRfcommManager; // feature_oem_rfcomm (Link to Windows' Bluetooth side channel)
    }

    @Override
    public void setPermissionGranted(int uid) {
        if (uid > 0) mPermissionGrantedUids.add(uid);
    }

    @Override
    public boolean getPermissionGranted(int uid) {
        return mPermissionGrantedUids.contains(uid);
    }

    @Override
    public void setSecurityToken(String token) {
        mSecurityToken = token;
    }

    @Override
    public void registerTaskChangeListener(ITaskChangeListener listener) {
        final long token = Binder.clearCallingIdentity();
        try {
            mTaskChangeNotifier.register(listener);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void unregisterTaskChangeListener(ITaskChangeListener listener) {
        final long token = Binder.clearCallingIdentity();
        try {
            mTaskChangeNotifier.unregister(listener);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public IProximityService getProximityService() {
        return null; // feature_proximity is false on OnePlus too
    }

    @Override
    public boolean isInstantHotspotCapable() {
        final long token = Binder.clearCallingIdentity();
        try {
            final TetheringManager tm = mContext.getSystemService(TetheringManager.class);
            if (tm == null) return false;
            // isTetheringSupported() is @SystemApi; call it reflectively so this file also
            // compiles against the public SDK (rooted-stock emulation build).
            return (Boolean) TetheringManager.class.getMethod("isTetheringSupported").invoke(tm);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Slog.w(TAG, "isTetheringSupported failed", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // ------------------------------------------------------------------ Remote Task glue
    // Package-private API used by the Remote Task WM patch (milestone 4). Mirrors OnePlus
    // CrossDeviceService + CrossDeviceServiceDelegate.

    void setRemoteTaskHost(RemoteTaskHost host) {
        mRemoteTaskHost = host;
    }

    boolean isAnyClientAlive() {
        return mRemoteCallbacks.getRegisteredCallbackCount() > 0;
    }

    String getSecurityToken() {
        return mSecurityToken;
    }

    List<String> getBackgroundActivityAllowlist() {
        return new ArrayList<>(mBackgroundActivityAllowlist);
    }

    /** {width, height, densityDpi} for new remote-task virtual displays. */
    int[] getRemoteDisplayInfo() {
        synchronized (mLock) {
            if (mDisplayInfoSet) {
                return new int[] {mDisplayWidth, mDisplayHeight, mDisplayDensityDpi};
            }
        }
        final android.util.DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        return new int[] {dm.widthPixels, dm.heightPixels, dm.densityDpi};
    }

    private interface CallbackAction {
        void run(IRemoteTaskCallback cb) throws RemoteException;
    }

    private void broadcast(String what, CallbackAction action) {
        Slog.i(TAG, what);
        synchronized (mRemoteCallbacks) {
            final int n = mRemoteCallbacks.beginBroadcast();
            try {
                for (int i = 0; i < n; i++) {
                    try {
                        action.run(mRemoteCallbacks.getBroadcastItem(i));
                    } catch (RemoteException e) {
                        Slog.w(TAG, what + " failed", e);
                    }
                }
            } finally {
                mRemoteCallbacks.finishBroadcast();
            }
        }
    }

    void activateRemoteTask(IServiceRemoteTaskInstance instance) {
        broadcast("activateRemoteTask", cb -> cb.activateRemoteTask(instance));
    }

    void notifyRemoteTaskRemoved(String uuid, int reason) {
        broadcast("notifyRemoteTaskRemoved " + uuid,
                cb -> cb.notifyRemoteTaskRemoved(uuid, reason));
    }

    void notifyDisplaySwitched(IServiceRemoteTaskInstance instance, Intent intent, int flag) {
        broadcast("notifyDisplaySwitched", cb -> cb.notifyDisplaySwitched(instance, intent, flag));
    }

    void notifyDeviceAvailabilityStateChanged(int state) {
        broadcast("notifyDeviceAvailabilityStateChanged " + state,
                cb -> cb.notifyDeviceAvailabilityStateChanged(state));
    }

    void notifyRemoteTaskEmptyUUIDetected(Intent intent) {
        broadcast("notifyRemoteTaskEmptyUUIDetected",
                cb -> cb.notifyRemoteTaskEmptyUUIDetected(intent));
    }

    void notifyRemoteTaskCreationFailed(String uuid, int reason) {
        broadcast("notifyRemoteTaskCreationFailed " + uuid,
                cb -> cb.notifyRemoteTaskCreationFailed(uuid, reason));
    }

    void notifyRemoteShowingSecuredContentChanged(IServiceRemoteTaskInstance instance,
            boolean secured) {
        broadcast("notifyRemoteShowingSecuredContentChanged",
                cb -> cb.notifyRemoteShowingSecuredContentChanged(instance, secured));
    }

    void notifyDuplicateRemoteTask(Intent intent, String needFocusUuid, String interceptUuid,
            int reason) {
        broadcast("notifyDuplicateRemoteTask " + interceptUuid,
                cb -> cb.notifyDuplicateRemoteTask(intent, needFocusUuid, interceptUuid, reason));
    }

    private void handleClientDied() {
        if (isAnyClientAlive()) return;
        final RemoteTaskHost host = mRemoteTaskHost;
        final long token = Binder.clearCallingIdentity();
        try {
            if (host != null) host.onAllClientsDied();
            mTaskChangeNotifier.releaseAll();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // ------------------------------------------------------------------ task change listeners

    /** OnePlus TaskChangeNotifier + TaskChangeWatcher: forwards onTaskStackChanged. */
    private static final class TaskChangeNotifier extends TaskStackListener {
        private final Object mLock = new Object();
        private final Map<IBinder, IBinder.DeathRecipient> mListeners = new ArrayMap<>();
        private final Map<IBinder, ITaskChangeListener> mListenerObjs = new ArrayMap<>();
        private HandlerThread mThread;
        private Handler mHandler;

        private static IActivityTaskManager atm() {
            return ActivityTaskManager.getService();
        }

        void register(ITaskChangeListener listener) {
            if (listener == null) return;
            final IBinder b = listener.asBinder();
            synchronized (mLock) {
                if (mListeners.containsKey(b)) return;
                final IBinder.DeathRecipient dr = () -> unregister(listener);
                try {
                    b.linkToDeath(dr, 0);
                } catch (RemoteException e) {
                    return; // already dead
                }
                mListeners.put(b, dr);
                mListenerObjs.put(b, listener);
                if (mThread == null) {
                    mThread = new HandlerThread("ltw_taskwatcher", Process.THREAD_PRIORITY_DEFAULT);
                    mThread.start();
                    mHandler = new Handler(mThread.getLooper());
                    try {
                        atm().registerTaskStackListener(this);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "registerTaskStackListener failed", e);
                    }
                }
            }
        }

        void unregister(ITaskChangeListener listener) {
            if (listener == null) return;
            final IBinder b = listener.asBinder();
            synchronized (mLock) {
                final IBinder.DeathRecipient dr = mListeners.remove(b);
                mListenerObjs.remove(b);
                if (dr != null) b.unlinkToDeath(dr, 0);
                if (mListeners.isEmpty()) stopLocked();
            }
        }

        void releaseAll() {
            synchronized (mLock) {
                for (Map.Entry<IBinder, IBinder.DeathRecipient> e : mListeners.entrySet()) {
                    e.getKey().unlinkToDeath(e.getValue(), 0);
                }
                mListeners.clear();
                mListenerObjs.clear();
                stopLocked();
            }
        }

        private void stopLocked() {
            if (mThread == null) return;
            try {
                atm().unregisterTaskStackListener(this);
            } catch (RemoteException e) {
                Slog.w(TAG, "unregisterTaskStackListener failed", e);
            }
            mHandler.removeCallbacksAndMessages(null);
            mThread.quitSafely();
            mThread = null;
            mHandler = null;
        }

        @Override
        public void onTaskStackChanged() {
            final Handler h;
            final List<ITaskChangeListener> targets;
            synchronized (mLock) {
                h = mHandler;
                targets = new ArrayList<>(mListenerObjs.values());
            }
            if (h == null) return;
            h.post(() -> {
                for (ITaskChangeListener l : targets) {
                    try {
                        l.onTaskChanged();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "onTaskChanged failed", e);
                    }
                }
            });
        }
    }
}
