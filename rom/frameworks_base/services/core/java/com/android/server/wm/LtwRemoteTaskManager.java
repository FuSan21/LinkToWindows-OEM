/*
 * SPDX-License-Identifier: MIT
 *
 * Link to Windows "Apps" (Remote Task) — milestone 4.
 *
 * Port of OnePlus 12 / OxygenOS 15 RemoteTaskHandlerManager + RemoteTaskHandler (oplus-services.jar,
 * docs/OEM_INTEGRATION_PLAN.md 8d), reduced to OnePlus scenario 0 ("client called"):
 *
 *   1. Link to Windows (com.microsoft.appmanager, a priv-app) starts the app with an options Bundle
 *      holding deviceintegration:remotetask.{launchOption=32, uuid, security.token}
 *      (appmanager RTHContainer.launch / RTHUtils.makeRemoteLaunchBundle).
 *   2. {@link #redirectLaunch} runs while those options are still a raw Bundle
 *      (ActivityTaskManagerService.startActivity*): if the token matches the one
 *      deviceintegrationservice set (CrossDeviceManager.setSecurityToken), create a trusted
 *      virtual display "MS_VIRTUAL_DISPLAY<n>" (same name/flags as OnePlus) and put its id in
 *      android.activity.launchDisplayId. The normal launch path puts the app there.
 *   3. Broadcast IRemoteTaskCallback.activateRemoteTask(instance). deviceintegrationservice reads the
 *      display id from instance.getBundle() and calls updateSurface(uuid, surface) with the encoder
 *      surface -> VirtualDisplay.setSurface.
 *   4. When the display's last task goes away, or the PC closes the window (removeRemoteTask), report
 *      notifyRemoteTaskRemoved and release the display.
 *
 * Only framework.jar APIs are used, so this single file serves both the frameworks/base patch and the
 * rooted-stock emulation module.
 */
package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IServiceRemoteTaskInstance;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Surface;
import android.view.WindowManagerGlobal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LtwRemoteTaskManager implements CrossDeviceService.RemoteTaskHost {
    private static final String TAG = "LtwRemoteTask";

    // RemoteTaskConstants (OnePlus) / appmanager RTHUtils
    static final String KEY_LAUNCH_OPTION = "deviceintegration:remotetask.launchOption";
    static final String KEY_UUID = "deviceintegration:remotetask.uuid";
    static final String KEY_SECURITY_TOKEN = "deviceintegration:remotetask.security.token";
    static final int REMOTE_TASK_FLAG_LAUNCH = 32;
    static final String KEY_LAUNCH_DISPLAY_ID = "android.activity.launchDisplayId";

    static final int CLOSE_REASON_SWITCH_TASK_VD_TO_MD = 0;
    static final int CLOSE_REASON_NORMAL_CLOSE = 2;
    static final int CLOSE_REASON_USER_CLOSE = 4;
    static final int CLOSE_REASON_SYSTEM_CLOSE = 5;

    /** RemoteTaskConstants.FLAG_SWITCH_TASK_VD_TO_MD (notifyDisplaySwitched). */
    static final int FLAG_SWITCH_TASK_VD_TO_MD = 0;
    /** ActivityManager.INTENT_SENDER_ACTIVITY (@hide). */
    static final int INTENT_SENDER_ACTIVITY = 2;

    /** RemoteTaskConstants.REASON_DUPLICATE_LAUNCH (notifyDuplicateRemoteTask). */
    static final int REASON_DUPLICATE_LAUNCH = 2;

    static final String VIRTUAL_DISPLAY_PREFIX = "MS_VIRTUAL_DISPLAY";
    /** OnePlus: PUBLIC | OWN_CONTENT_ONLY | ROTATES_WITH_CONTENT | TRUSTED. */
    static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | (1 << 7) /* VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT */
            | (1 << 10) /* VIRTUAL_DISPLAY_FLAG_TRUSTED */;
    /** WindowManager.DISPLAY_IME_POLICY_LOCAL: the IME shows on the remote display itself. */
    static final int DISPLAY_IME_POLICY_LOCAL = 0;

    private static volatile LtwRemoteTaskManager sInstance;

    private final Context mContext;
    private final CrossDeviceService mService;
    private final DisplayManager mDisplayManager;
    private final Handler mHandler;
    private final Map<String, RemoteTask> mTasks = new ConcurrentHashMap<>();
    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskRemoved(int taskId) {
            mHandler.post(LtwRemoteTaskManager.this::reapEmptyDisplays);
        }

        @Override
        public void onTaskDisplayChanged(int taskId, int newDisplayId) {
            mHandler.post(() -> onTaskMoved(taskId, newDisplayId));
        }

        @Override
        public void onTaskStackChanged() {
            mHandler.post(LtwRemoteTaskManager.this::reapEmptyDisplays);
        }
    };
    private boolean mListening;
    private final SecureContentProbe mSecureProbe = new SecureContentProbe();
    /** OnePlus reports secure-content changes as they happen; poll while remote tasks exist. */
    private static final long SECURE_POLL_MS = 1000;
    private final Runnable mSecurePoll = new Runnable() {
        @Override
        public void run() {
            if (mTasks.isEmpty()) return;
            for (RemoteTask task : mTasks.values()) {
                final boolean secure = mSecureProbe.isFocusedWindowSecure(task.getDisplayId());
                if (secure != task.mShowingSecure) {
                    task.mShowingSecure = secure;
                    Slog.i(TAG, "remote task " + task.mUuid + " secure content=" + secure);
                    mService.notifyRemoteShowingSecuredContentChanged(task, secure);
                }
            }
            mHandler.postDelayed(this, SECURE_POLL_MS);
        }
    };

    LtwRemoteTaskManager(Context context, CrossDeviceService service) {
        mContext = context;
        mService = service;
        mDisplayManager = context.getSystemService(DisplayManager.class);
        HandlerThread t = new HandlerThread("ltw_remotetask");
        t.start();
        mHandler = new Handler(t.getLooper());
        sInstance = this;
    }

    /**
     * Hook point: called from ActivityTaskManagerService with the caller's raw options Bundle,
     * before it is parsed into ActivityOptions.
     *
     * @return the Bundle to use (possibly a copy with launchDisplayId set), never throws.
     */
    public static Bundle redirectLaunch(Bundle options, Intent intent, int callingUid) {
        final LtwRemoteTaskManager m = sInstance;
        if (m == null || options == null) return options;
        try {
            return m.redirectLaunchInternal(options, intent, callingUid);
        } catch (Throwable t) {
            Slog.e(TAG, "redirectLaunch failed", t);
            return options;
        }
    }

    /**
     * Hook point for PendingIntent launches (PendingIntentRecord.sendInner): Link to Windows opens
     * notifications with the remote-task options (RTHContainerManager.sendPendingIntentWithRemoteFlag,
     * flag + token, no uuid). Only activity PendingIntents are redirected.
     */
    public static Bundle redirectPendingIntentLaunch(Bundle options, int pendingIntentType,
            Intent intent, int callingUid) {
        if (pendingIntentType != INTENT_SENDER_ACTIVITY) return options;
        return redirectLaunch(options, intent, callingUid);
    }

    /**
     * OnePlus BackgroundActivityStartControllerExtImpl.isFromBackgroundWhiteList: while a remote-task
     * client is connected, the packages deviceintegrationservice registered with
     * CrossDeviceManager.updateBackgroundActivityList ("com.microsoft.appmanager", ".dev") may start
     * activities from the background as the real caller. Needed for notification launches: Link to
     * Windows is the PendingIntent sender while the notifying app (the creator) is in the background.
     */
    public static boolean isRealCallerBackgroundStartAllowed(String realCallingPackage) {
        final LtwRemoteTaskManager m = sInstance;
        return m != null && realCallingPackage != null && m.mService.isAnyClientAlive()
                && m.mService.getBackgroundActivityAllowlist().contains(realCallingPackage);
    }

    /**
     * OnePlus scenario 1 ("app to app"): an app streamed in a PC window starts ANOTHER app in a new
     * task (e.g. a link opening the browser). Without help it would land on the same remote display,
     * inside the first app's PC window; OnePlus gives it its own remote display / PC window.
     * Called for every startActivityAsUser that does not already carry the remote-task options.
     */
    public static Bundle redirectAppToApp(Bundle options, Intent intent, int callingUid,
            String callingPackage) {
        final LtwRemoteTaskManager m = sInstance;
        if (m == null || intent == null || callingPackage == null || m.mTasks.isEmpty()) {
            return options;
        }
        if (options != null && (options.containsKey(KEY_LAUNCH_OPTION)
                || options.containsKey(KEY_LAUNCH_DISPLAY_ID))) {
            return options; // Link to Windows launch, or the caller chose a display itself
        }
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            return options; // joins the caller's task: stays in the caller's window (as OnePlus)
        }
        try {
            final RemoteTask host = m.findTaskHosting(callingPackage);
            if (host == null || !m.mService.isAnyClientAlive()) {
                return options;
            }
            // Compare with the app that owns the caller's window, not with the caller: browsers
            // and others trampoline (e.g. Brave's IntentDispatcher runs inside Facebook's task on
            // the remote display, then starts Brave's own activity in a NEW_TASK).
            final String windowApp = host.basePackage();
            final String target = m.targetPackage(intent);
            if (target == null || target.equals(windowApp) || isHomeIntent(intent)) {
                return options;
            }
            final Bundle remote = options != null ? new Bundle(options) : new Bundle();
            remote.putInt(KEY_LAUNCH_OPTION, REMOTE_TASK_FLAG_LAUNCH);
            remote.putString(KEY_SECURITY_TOKEN, m.mService.getSecurityToken());
            Slog.i(TAG, "app-to-app: " + callingPackage + " (window " + windowApp + ") -> "
                    + target);
            return m.redirectLaunchInternal(remote, intent, callingUid);
        } catch (Throwable t) {
            Slog.e(TAG, "redirectAppToApp failed", t);
            return options;
        }
    }

    private static boolean isHomeIntent(Intent intent) {
        return Intent.ACTION_MAIN.equals(intent.getAction()) && intent.getCategories() != null
                && intent.getCategories().contains(Intent.CATEGORY_HOME);
    }

    /** Package the intent will start (explicit, or resolved as the system would). */
    private String targetPackage(Intent intent) {
        if (intent.getComponent() != null) return intent.getComponent().getPackageName();
        if (intent.getPackage() != null) return intent.getPackage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final android.content.pm.ResolveInfo ri = mContext.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * The PC window owned by {@code pkg} (base activity of its top task), or null. Used for
     * duplicate launches; unlike {@link #findTaskHosting} it ignores another app's activity
     * that is merely on top (a trampoline such as a browser's intent dispatcher).
     */
    private RemoteTask findWindowOwnedBy(String pkg) {
        for (RemoteTask task : mTasks.values()) {
            if (pkg.equals(task.basePackage())) return task;
        }
        return null;
    }

    /** The remote task whose display currently shows {@code pkg} (top or base), or null. */
    private RemoteTask findTaskHosting(String pkg) {
        for (RemoteTask task : mTasks.values()) {
            final ActivityManager.RunningTaskInfo top = task.topTask();
            if (top == null) continue;
            if ((top.topActivity != null && pkg.equals(top.topActivity.getPackageName()))
                    || (top.baseActivity != null
                            && pkg.equals(top.baseActivity.getPackageName()))) {
                return task;
            }
        }
        return null;
    }

    private Bundle redirectLaunchInternal(Bundle options, Intent intent, int callingUid) {
        if (options.getInt(KEY_LAUNCH_OPTION, 0) != REMOTE_TASK_FLAG_LAUNCH) return options;
        final String expected = mService.getSecurityToken();
        final String token = options.getString(KEY_SECURITY_TOKEN);
        if (TextUtils.isEmpty(expected) || !TextUtils.equals(expected, token)) {
            Slog.w(TAG, "remote launch from uid " + callingUid + " rejected: token mismatch");
            return options;
        }
        if (!mService.isAnyClientAlive()) {
            Slog.w(TAG, "remote launch ignored: no deviceintegrationservice callback");
            return options;
        }
        String uuid = options.getString(KEY_UUID);
        if (TextUtils.isEmpty(uuid)) uuid = UUID.randomUUID().toString();

        final Bundle out = new Bundle(options);
        RemoteTask task = mTasks.get(uuid);
        if (task != null && task.isAlive()) {
            // Same remote window launching again (e.g. a notification): reuse its display.
            out.putInt(KEY_LAUNCH_DISPLAY_ID, task.getDisplayId());
            return out;
        }

        // OnePlus scenario 3 (duplicate launch): the app already has a PC window. Don't create a
        // second display; launch into the existing one and let deviceintegrationservice focus that
        // window and drop the new request (it handles notifyDuplicateRemoteTask exactly so).
        final String target = targetPackage(intent);
        final RemoteTask existing = target != null ? findWindowOwnedBy(target) : null;
        if (existing != null) {
            final String interceptUuid = uuid;
            Slog.i(TAG, "duplicate launch of " + target + ": focus " + existing.mUuid);
            out.putInt(KEY_LAUNCH_DISPLAY_ID, existing.getDisplayId());
            out.putString(KEY_UUID, existing.mUuid);
            mHandler.post(() -> mService.notifyDuplicateRemoteTask(intent, existing.mUuid,
                    interceptUuid, REASON_DUPLICATE_LAUNCH));
            return out;
        }

        final int[] info = mService.getRemoteDisplayInfo();
        int w = info[0], h = info[1];
        if (w > h) { // OnePlus: remote task displays start portrait
            int tmp = w;
            w = h;
            h = tmp;
        }
        final VirtualDisplay vd;
        final long ident = Binder.clearCallingIdentity();
        try {
            vd = mDisplayManager.createVirtualDisplay(
                    VIRTUAL_DISPLAY_PREFIX + (mDisplayManager.getDisplays().length + 1),
                    w, h, info[2], null /* surface: set later by updateSurface */,
                    VIRTUAL_DISPLAY_FLAGS);
            if (vd == null) {
                Slog.e(TAG, "createVirtualDisplay returned null");
                return options;
            }
            try {
                WindowManagerGlobal.getWindowManagerService().setDisplayImePolicy(
                        vd.getDisplay().getDisplayId(), DISPLAY_IME_POLICY_LOCAL);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "setDisplayImePolicy failed", e);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        final int displayId = vd.getDisplay().getDisplayId();
        out.putInt(KEY_LAUNCH_DISPLAY_ID, displayId);
        out.putString(KEY_UUID, uuid);

        task = new RemoteTask(uuid, vd, intent, out, target);
        mTasks.put(uuid, task);
        ensureListening();
        Slog.i(TAG, "remote task " + uuid + " -> display " + displayId + " (" + w + "x" + h
                + "@" + info[2] + ") for " + task.mPackageName);
        final RemoteTask activated = task;
        mHandler.post(() -> mService.activateRemoteTask(activated));
        mHandler.removeCallbacks(mSecurePoll);
        mHandler.postDelayed(mSecurePoll, SECURE_POLL_MS);
        return out;
    }

    // ------------------------------------------------------------------ RemoteTaskHost

    @Override
    public void updateSurface(String uuid, Surface surface) {
        final RemoteTask task = uuid != null ? mTasks.get(uuid) : null;
        if (task == null) {
            Slog.w(TAG, "updateSurface: unknown remote task " + uuid);
            return;
        }
        task.mDisplay.setSurface(surface);
    }

    @Override
    public void onAllClientsDied() {
        for (RemoteTask task : mTasks.values()) {
            close(task, CLOSE_REASON_SYSTEM_CLOSE, true, false);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Runs inside the launching app's binder call: use system_server's identity. */
    private synchronized void ensureListening() {
        if (mListening) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
            mListening = true;
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "registerTaskStackListener failed", e); // non-fatal: PC close still works
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** A remote display whose app went away (user/back/finish): tell the PC and release it. */
    private void reapEmptyDisplays() {
        for (RemoteTask task : mTasks.values()) {
            final List<ActivityManager.RunningTaskInfo> tasks = task.tasksOnDisplay();
            if (tasks == null) continue;
            if (!tasks.isEmpty()) {
                task.mHadTask = true;
                for (ActivityManager.RunningTaskInfo t : tasks) task.mTaskIds.add(t.taskId);
            } else if (task.mHadTask) {
                close(task, CLOSE_REASON_USER_CLOSE, false, true);
            }
        }
    }

    /**
     * A streamed task moved displays. To the phone screen (display 0) = the user opened it on the
     * phone: like OnePlus handleTaskSwitchSession (VD->MD), report notifyDisplaySwitched(flag 0)
     * and release the remote display without notifyRemoteTaskRemoved (deviceintegrationservice
     * closes the PC window itself on that flag).
     */
    private void onTaskMoved(int taskId, int newDisplayId) {
        for (RemoteTask task : mTasks.values()) {
            if (!task.mTaskIds.contains(taskId) || newDisplayId == task.getDisplayId()) continue;
            final List<ActivityManager.RunningTaskInfo> left = task.tasksOnDisplay();
            if (newDisplayId == android.view.Display.DEFAULT_DISPLAY
                    && (left == null || left.isEmpty())) {
                Slog.i(TAG, "remote task " + task.mUuid + " moved to the phone screen");
                mService.notifyDisplaySwitched(task, task.mIntent, FLAG_SWITCH_TASK_VD_TO_MD);
                close(task, CLOSE_REASON_SWITCH_TASK_VD_TO_MD, false, false);
                return;
            }
        }
        reapEmptyDisplays();
    }

    void close(RemoteTask task, int reason, boolean removeTasks, boolean notify) {
        if (mTasks.remove(task.mUuid) == null) return;
        Slog.i(TAG, "close remote task " + task.mUuid + " reason=" + reason);
        if (removeTasks) task.removeTasks();
        task.mDisplay.release();
        if (notify) mService.notifyRemoteTaskRemoved(task.mUuid, reason);
    }

    // ------------------------------------------------------------------ secure content

    /**
     * Is the focused window on a display FLAG_SECURE? (OnePlus: displayContent.mCurrentFocus.mAttrs
     * .flags & FLAG_SECURE.) Read reflectively from WindowManagerService, which ServiceManager hands
     * out as the local object inside system_server, so this one source also works from the rooted
     * emulation module (a different class loader, where package-private access would fail).
     */
    static final class SecureContentProbe {
        private Object mWms;
        private Object mLock;
        private Object mRoot;
        private java.lang.reflect.Method mGetDisplayContent;
        private java.lang.reflect.Field mCurrentFocus;
        private java.lang.reflect.Field mAttrs;
        private boolean mFailed;

        synchronized boolean isFocusedWindowSecure(int displayId) {
            if (mFailed) return false;
            try {
                if (mWms == null) {
                    mWms = android.os.ServiceManager.getService(Context.WINDOW_SERVICE);
                    mLock = field(mWms.getClass(), "mGlobalLock").get(mWms);
                    mRoot = field(mWms.getClass(), "mRoot").get(mWms);
                    mGetDisplayContent = method(mRoot.getClass(), "getDisplayContent", int.class);
                }
                final Object focus;
                synchronized (mLock) {
                    final Object dc = mGetDisplayContent.invoke(mRoot, displayId);
                    if (dc == null) return false;
                    if (mCurrentFocus == null) mCurrentFocus = field(dc.getClass(), "mCurrentFocus");
                    focus = mCurrentFocus.get(dc);
                    if (focus == null) return false;
                    if (mAttrs == null) mAttrs = field(focus.getClass(), "mAttrs");
                    final android.view.WindowManager.LayoutParams attrs =
                            (android.view.WindowManager.LayoutParams) mAttrs.get(focus);
                    return (attrs.flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                Slog.w(TAG, "secure-content probe unavailable", e);
                mFailed = true; // degrade to "not secure" (the PC then shows SurfaceFlinger's black)
                return false;
            }
        }

        private static java.lang.reflect.Field field(Class<?> c, String name)
                throws NoSuchFieldException {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // walk up
                }
            }
            throw new NoSuchFieldException(name);
        }

        private static java.lang.reflect.Method method(Class<?> c, String name, Class<?>... p)
                throws NoSuchMethodException {
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Method m = k.getDeclaredMethod(name, p);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // walk up
                }
            }
            throw new NoSuchMethodException(name);
        }
    }

    // ------------------------------------------------------------------ one remote task

    /** == OnePlus RemoteTaskHandler: the IServiceRemoteTaskInstance handed to deviceintegrationservice. */
    final class RemoteTask extends IServiceRemoteTaskInstance.Stub {
        final String mUuid;
        final VirtualDisplay mDisplay;
        final Intent mIntent;
        final Bundle mBundle;
        final String mPackageName;
        volatile boolean mHasFocus;
        volatile boolean mHadTask;
        volatile boolean mShowingSecure;
        final java.util.Set<Integer> mTaskIds =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        RemoteTask(String uuid, VirtualDisplay display, Intent intent, Bundle bundle,
                String resolvedPackage) {
            mUuid = uuid;
            mDisplay = display;
            mIntent = intent;
            mBundle = bundle;
            // Implicit intents (e.g. a link: ACTION_VIEW without package/component) used to give
            // null here, and Link to Windows then never opened a PC window for the task.
            mPackageName = resolvedPackage;
        }

        int getDisplayId() {
            return mDisplay.getDisplay().getDisplayId();
        }

        boolean isAlive() {
            return mTasks.get(mUuid) == this;
        }

        List<ActivityManager.RunningTaskInfo> tasksOnDisplay() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return ActivityTaskManager.getService().getTasks(Integer.MAX_VALUE,
                        false /* filterOnlyVisibleRecents */, false /* keepIntentExtra */,
                        getDisplayId());
            } catch (RemoteException | RuntimeException e) {
                return null;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /** Package of the app that owns this PC window (base activity of its top task). */
        String basePackage() {
            final ActivityManager.RunningTaskInfo top = topTask();
            if (top != null && top.baseActivity != null) return top.baseActivity.getPackageName();
            return mPackageName; // may be null only if the display is empty too
        }

        ActivityManager.RunningTaskInfo topTask() {
            final List<ActivityManager.RunningTaskInfo> tasks = tasksOnDisplay();
            return tasks == null || tasks.isEmpty() ? null : tasks.get(0);
        }

        void removeTasks() {
            final List<ActivityManager.RunningTaskInfo> tasks = tasksOnDisplay();
            if (tasks == null) return;
            final long ident = Binder.clearCallingIdentity();
            try {
                for (ActivityManager.RunningTaskInfo t : tasks) {
                    ActivityTaskManager.getService().removeTask(t.taskId);
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "removeTask failed", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private Configuration topConfiguration() {
            final ActivityManager.RunningTaskInfo t = topTask();
            if (t == null) return null;
            try { // TaskInfo.configuration is @hide; read it reflectively (compiles on the SDK)
                return (Configuration) android.app.TaskInfo.class.getField("configuration").get(t);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        // ---- IServiceRemoteTaskInstance

        @Override
        public boolean isTouchRequired() {
            if (mPackageName == null) return false;
            final long ident = Binder.clearCallingIdentity();
            try {
                FeatureInfo[] features = mContext.getPackageManager()
                        .getPackageInfo(mPackageName, PackageManager.GET_CONFIGURATIONS).reqFeatures;
                if (features != null) {
                    for (FeatureInfo f : features) {
                        if (PackageManager.FEATURE_TOUCHSCREEN.equals(f.name)
                                && (f.flags & FeatureInfo.FLAG_REQUIRED) != 0) {
                            return true;
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // fall through
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return false;
        }

        @Override
        public int getOrientation() {
            final Configuration c = topConfiguration();
            return c != null ? c.orientation : Configuration.ORIENTATION_UNDEFINED;
        }

        @Override
        public Rect getRemoteTaskBounds() {
            final Configuration c = topConfiguration();
            if (c == null) return null;
            try {
                Object wc = Configuration.class.getField("windowConfiguration").get(c);
                return (Rect) wc.getClass().getMethod("getBounds").invoke(wc);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        @Override
        public boolean isRemoteTaskResizeable() {
            return false; // same as OnePlus
        }

        @Override
        public String getRemoteTaskInstanceUUID() {
            return mUuid;
        }

        @Override
        public Intent getRemoteTaskIntent() {
            return mIntent;
        }

        @Override
        public String getRemoteTaskInstancePackageId() {
            return mPackageName != null ? mPackageName : basePackage();
        }

        @Override
        public boolean isRemoteTaskInstanceShowingSecuredContent() {
            // OnePlus RemoteTaskHandler: FLAG_SECURE on the remote display's focused window.
            return mSecureProbe.isFocusedWindowSecure(getDisplayId());
        }

        @Override
        public boolean isRemoteTaskInstanceInFocus() {
            return mHasFocus;
        }

        @Override
        public Bundle getBundle() {
            return mBundle; // carries android.activity.launchDisplayId (read by deviceintegrationservice)
        }

        @Override
        public void removeRemoteTask() {
            mHandler.post(() -> close(this, CLOSE_REASON_NORMAL_CLOSE, true, true));
        }

        @Override
        public void changeRemoteTaskFocus(boolean inFocus) {
            mHasFocus = inFocus;
        }

        @Override
        public void notifyRemoteDisplayResolutionChanged(Rect bounds, int dpi) {
            // OnePlus ignores this too.
        }

        @Override
        public void notifyRemoteDisplayOrientationChanged(int orientation) {
            // TODO: OnePlus requests the task's orientation; displays rotate with content meanwhile.
        }

        @Override
        public void notifyRemoteTaskInputEvent() {
        }
    }
}
