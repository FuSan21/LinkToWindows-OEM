package me.ayra.ltw.emu;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import com.android.server.wm.CrossDeviceService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Rooted-stock EMULATION of what the LineageOS 24 OEM build (rom/patches/frameworks_base/0001)
 * provides natively. It does exactly three things and nothing else:
 *
 * <ol>
 *   <li><b>system_server:</b> registers {@code cross_device_service}, using the SAME
 *       {@link CrossDeviceService} source as the framework patch (patch: SystemServer
 *       startOtherServices; here: after ActivityManagerService.systemReady).</li>
 *   <li><b>system_server:</b> answers "granted" for the signature-only permissions a platform
 *       certificate would give the broker ({@link #EMULATED_PLATFORM_PERMISSIONS}), for
 *       {@link #BROKER_PKG} ONLY. Microsoft's apps get nothing from this module.</li>
 *   <li><b>deviceintegrationservice:</b> {@code getSystemService("cross_device_service")} returns
 *       android.app.CrossDeviceManager loaded from the caller's ClassLoader (patch:
 *       SystemServiceRegistry.registerCrossDeviceService).</li>
 * </ol>
 *
 * Everything else (priv-app placement, privapp allowlist, hidden-API sysconfig, the
 * com.microsoft.device-private library) is provided by files, via the ltw_oem_emulation
 * KernelSU module, exactly as on the OEM build. No hooks into Microsoft code.
 *
 * Every hook is fail-safe: a Throwable in system_server would bootloop the device.
 */
public class LtwEmulationModule extends XposedModule {

    private static final String TAG = "LtwEmu";

    static final String SERVICE_NAME = "cross_device_service";
    static final String BROKER_PKG = "com.microsoftsdk.crossdeviceservicebroker";
    static final String DIS_PKG = "com.microsoft.deviceintegrationservice";
    private static final String MANAGER_CLASS = "android.app.CrossDeviceManager";

    /** signature-level on Android 17 => only a platform cert gets them on the OEM build. */
    static final Set<String> EMULATED_PLATFORM_PERMISSIONS = new HashSet<>(Arrays.asList(
            "android.permission.MANAGE_MEDIA_PROJECTION",
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
            // Defined by deviceintegrationservice (knownSigner|signature, knownCerts = appmanager's
            // cert). Guards MediaProjectionPermissionActivity, which DIS asks the broker to launch.
            // OnePlus' broker requests it; assumed granted there via its platform signature
            // (UNVERIFIED — see docs §9).
            "deviceintegration.permission.ACCESS_DEVICE_INTEGRATION",
            // signature|recents. Makes deviceintegrationservice use the broker's black screen
            // (V2) and lets the broker's gesture monitor keep phone touches from the apps below.
            "android.permission.MONITOR_INPUT",
            // signature. Broker reads the UDFPS location so it never blocks fingerprint touches.
            "android.permission.USE_BIOMETRIC_INTERNAL"));

    /** Broker's BlackScreen.ACTION_HIDE (power key, V2 black screen). */
    static final String ACTION_HIDE_BLACK_SCREEN =
            "com.microsoftsdk.crossdeviceservicebroker.action.HIDE_BLACK_SCREEN";

    /** OnePlus framework window types (WindowManager.LayoutParams, oplus framework.jar). */
    static final int TYPE_DRAGDROP_OVERLAY = 2098;
    static final int TYPE_BLACKSCREEN_OVERLAY = 2099;
    /** WindowManagerPolicy layer of TYPE_SECURE_SYSTEM_OVERLAY on Android 17. */
    static final int LTW_OVERLAY_LAYER = 33;

    private boolean mPermHooksInstalled;
    private boolean mWindowHooksInstalled;
    private boolean mLaunchHookInstalled;

    /**
     * Patch 0005 equivalent: ActivityTaskManagerService.startActivityAsUser hands the caller's raw
     * options Bundle to LtwRemoteTaskManager.redirectLaunch (Link to Windows "Apps").
     */
    private int installRemoteTaskLaunchHook(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> atms = Class.forName("com.android.server.wm.ActivityTaskManagerService",
                    false, cl);
            for (Method m : atms.getDeclaredMethods()) {
                if (!m.getName().equals("startActivityAsUser")) continue;
                final Class<?>[] p = m.getParameterTypes();
                int intentIdx = -1, bundleIdx = -1;
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == android.content.Intent.class && intentIdx < 0) intentIdx = i;
                    if (p[i] == android.os.Bundle.class) bundleIdx = i;
                }
                if (intentIdx < 0 || bundleIdx < 0) continue;
                final int ii = intentIdx, bi = bundleIdx;
                final int pi = p.length > 1 && p[1] == String.class ? 1 : -1; // callingPackage
                hook(m).intercept(chain -> {
                    final Object[] args = chain.getArgs().toArray();
                    final android.os.Bundle in = (android.os.Bundle) args[bi];
                    final android.content.Intent intent = (android.content.Intent) args[ii];
                    final int uid = android.os.Binder.getCallingUid();
                    // == ActivityTaskManagerService.startActivityAsUser in patches 0005/0008
                    android.os.Bundle out = com.android.server.wm.LtwRemoteTaskManager
                            .redirectLaunch(in, intent, uid);
                    if (pi >= 0) {
                        out = com.android.server.wm.LtwRemoteTaskManager.redirectAppToApp(out,
                                intent, uid, (String) args[pi]);
                    }
                    if (out == in) return chain.proceed();
                    args[bi] = out;
                    return chain.proceed(args);
                });
                n++;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "startActivityAsUser hook failed", t);
        }
        // Notification launches: PendingIntent.send(..., remote bundle) -> PendingIntentRecord.sendInner
        try {
            Class<?> pir = Class.forName("com.android.server.am.PendingIntentRecord", false, cl);
            final Field keyField = findField(pir, "key");
            for (Method m : pir.getDeclaredMethods()) {
                if (!m.getName().equals("sendInner")) continue;
                final Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || p[0] != Class.forName("android.app.IApplicationThread")) {
                    continue; // only the full overload; the short one delegates to it
                }
                final int bi = p.length - 1;
                if (p[bi] != android.os.Bundle.class) continue;
                hook(m).intercept(chain -> {
                    final Object[] args = chain.getArgs().toArray();
                    final android.os.Bundle in = (android.os.Bundle) args[bi];
                    if (in == null || !in.containsKey("deviceintegration:remotetask.launchOption")) {
                        return chain.proceed();
                    }
                    final Object key = keyField.get(chain.getThisObject());
                    final int type = findField(key.getClass(), "type").getInt(key);
                    final android.content.Intent req =
                            (android.content.Intent) findField(key.getClass(), "requestIntent").get(key);
                    args[bi] = com.android.server.wm.LtwRemoteTaskManager
                            .redirectPendingIntentLaunch(in, type, req,
                                    android.os.Binder.getCallingUid());
                    return chain.proceed(args);
                });
                n++;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "PendingIntentRecord.sendInner hook failed", t);
        }
        // BAL: allow deviceintegrationservice's registered packages as the real caller (patch 0006).
        try {
            Class<?> bal = Class.forName(
                    "com.android.server.wm.BackgroundActivityStartController", false, cl);
            Class<?> stateClass = Class.forName(
                    "com.android.server.wm.BackgroundActivityStartController$BalState", false, cl);
            Class<?> verdictClass = Class.forName(
                    "com.android.server.wm.BackgroundActivityStartController$BalVerdict", false, cl);
            final Field realPkg = findField(stateClass, "mRealCallingPackage");
            final int allowPermission = resolveBalCode(bal, "BAL_ALLOW_PERMISSION");
            final java.lang.reflect.Constructor<?> ctor =
                    verdictClass.getDeclaredConstructor(int.class, String.class);
            ctor.setAccessible(true);
            Method check = bal.getDeclaredMethod("checkBackgroundActivityStartAllowedByRealCaller",
                    stateClass);
            log(Log.INFO, TAG, "BAL_ALLOW_PERMISSION = " + allowPermission);
            hook(check).intercept(chain -> {
                final String pkg = (String) realPkg.get(chain.getArg(0));
                final boolean allow = com.android.server.wm.LtwRemoteTaskManager
                        .isRealCallerBackgroundStartAllowed(pkg);
                log(Log.INFO, TAG, "BAL real caller " + pkg + " -> ltw allow=" + allow
                        + " (BAL_ALLOW_PERMISSION=" + allowPermission + ")");
                if (allow) {
                    return ctor.newInstance(allowPermission,
                            "Link to Windows remote task allowlist (" + pkg + ")");
                }
                return chain.proceed();
            });
            n++;
            // system_server is AOT-compiled; the caller may have the callee inlined, which would
            // bypass the hook. Force the caller(s) to the interpreter.
            for (Method m : bal.getDeclaredMethods()) {
                if (m.getName().equals("checkBackgroundActivityStart")) {
                    log(Log.INFO, TAG, "deoptimize " + m.getName() + ": " + deoptimize(m));
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "BAL real-caller hook failed", t);
        }
        log(Log.INFO, TAG, "remote task launch hooks: " + n);
        return n;
    }
    private boolean mServiceHookInstalled;
    private boolean mFetcherInstalled;
    private volatile boolean mServiceRegistered;
    private volatile int mBrokerAppId = -1;
    private static volatile Object sManager;

    // ------------------------------------------------------------------ entry points

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
        if (param.isSystemServer()) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            installSystemServer(cl != null ? cl : ClassLoader.getSystemClassLoader(), "onModuleLoaded");
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        installSystemServer(param.getClassLoader(), "onSystemServerStarting");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (DIS_PKG.equals(param.getPackageName()) && param.isFirstPackage()) {
            installFetcher();
        }
    }

    // ------------------------------------------------------------------ system_server

    private synchronized void installSystemServer(ClassLoader cl, String from) {
        if (!mServiceHookInstalled) {
            mServiceHookInstalled = hookAll(cl, "com.android.server.am.ActivityManagerService",
                    "systemReady", chain -> {
                        Object r = chain.proceed();
                        registerService(chain.getThisObject());
                        return r;
                    }) > 0;
        }
        if (!mWindowHooksInstalled) {
            mWindowHooksInstalled = installWindowPolicyHooks(cl);
        }
        if (!mLaunchHookInstalled) {
            mLaunchHookInstalled = installRemoteTaskLaunchHook(cl) > 0;
        }
        if (!mPermHooksInstalled) {
            int n = 0;
            n += hookPermissionChecks(cl, "com.android.server.pm.permission.PermissionManagerServiceImpl",
                    "checkPermission", "checkUidPermission");
            n += hookPermissionChecks(cl, "com.android.server.pm.permission.PermissionManagerService",
                    "checkPermission", "checkUidPermission");
            n += hookPermissionChecks(cl, "com.android.server.am.ActivityManagerService",
                    "checkPermission");
            n += hookPermissionChecks(cl, "android.app.ActivityManager", "checkComponentPermission");
            mPermHooksInstalled = n > 0;
        }
        log(Log.INFO, TAG, "system_server install via " + from + ": serviceHook="
                + mServiceHookInstalled + " permHooks=" + mPermHooksInstalled);
    }

    /**
     * OnePlus PhoneWindowManagerExtImpl.overrideCheckAddPermission: window types 2098
     * (TYPE_SYSTEM_DRAGDROP_OVERLAY) and 2099 (TYPE_SYSTEM_BLACKSCREEN_OVERLAY) may be added iff
     * CrossDeviceManager.isCallerAllowed (deviceintegrationservice holding MANAGE_CROSS_DEVICE).
     * AOSP has no such types, so also give them a real layer (OnePlus: 118 / 70 in its custom
     * layer scheme; here TYPE_SECURE_SYSTEM_OVERLAY's 33) instead of "Unknown window type" -> 3.
     */
    private boolean installWindowPolicyHooks(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> pwm = Class.forName("com.android.server.policy.PhoneWindowManager", false, cl);
            Method check = pwm.getDeclaredMethod("checkAddPermission", int.class, boolean.class,
                    String.class, int[].class, int.class);
            hook(check).intercept(chain -> {
                final int type = (Integer) chain.getArg(0);
                if (type == TYPE_DRAGDROP_OVERLAY || type == TYPE_BLACKSCREEN_OVERLAY) {
                    final Context ctx = (Context) findField(chain.getThisObject().getClass(),
                            "mContext").get(chain.getThisObject());
                    if (isCrossDeviceCallerAllowed(ctx)) {
                        ((int[]) chain.getArg(3))[0] = -1; // AppOpsManager.OP_NONE
                        return 0; // WindowManagerGlobal.ADD_OKAY
                    }
                    return -8; // ADD_PERMISSION_DENIED (same as OnePlus)
                }
                return chain.proceed();
            });
            n++;
            Class<?> policy = Class.forName("com.android.server.policy.WindowManagerPolicy",
                    false, cl);
            Method layer = policy.getDeclaredMethod("getWindowLayerFromTypeLw", int.class,
                    boolean.class, boolean.class);
            hook(layer).intercept(chain -> {
                final int type = (Integer) chain.getArg(0);
                if (type == TYPE_DRAGDROP_OVERLAY || type == TYPE_BLACKSCREEN_OVERLAY) {
                    return LTW_OVERLAY_LAYER;
                }
                return chain.proceed();
            });
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "window policy hooks failed", t);
        }
        n += installBlackScreenSurfaceHooks(cl);
        n += installPowerKeyHook(cl);
        log(Log.INFO, TAG, "window policy hooks: " + n);
        return n >= 2;
    }

    /**
     * OnePlus treats type 2099 like a rounded-corner overlay in services.jar (patch 0003):
     *  - WindowStateAnimator.createSurfaceLocked: surface flag SKIP_SCREENSHOT, so SurfaceFlinger
     *    keeps the black screen out of screenshots and of the Link to Windows mirror
     *    (YourPhoneCompanion), while the phone itself shows it;
     *  - InputMonitor.isTrustedOverlay: 2098/2099 are trusted overlays.
     * Here: set skip-screenshot on the freshly created surface (same effect as the flag).
     */
    private int installBlackScreenSurfaceHooks(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> wsa = Class.forName("com.android.server.wm.WindowStateAnimator", false, cl);
            Method create = wsa.getDeclaredMethod("createSurfaceLocked");
            final Field winField = findField(wsa, "mWin");
            final Field scField = findField(wsa, "mSurfaceControl");
            hook(create).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object win = winField.get(chain.getThisObject());
                    WindowManagerParams p = WindowManagerParams.of(win);
                    if (p != null && p.type == TYPE_BLACKSCREEN_OVERLAY) {
                        Object sc = scField.get(chain.getThisObject());
                        if (sc != null) setSkipScreenshot(sc);
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "skip-screenshot for black screen failed", t);
                }
                return result;
            });
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "createSurfaceLocked hook failed", t);
        }
        // Android 17: window surfaces are usually created client-side and handed over through
        // WindowState.setClientSurface (createSurfaceLocked is not used). Mark the WindowState's
        // own container surface (parent of the client surface) skip-screenshot instead; the flag
        // covers the whole subtree.
        try {
            Class<?> ws = Class.forName("com.android.server.wm.WindowState", false, cl);
            Method setClient = ws.getDeclaredMethod("setClientSurface",
                    Class.forName("android.view.SurfaceControl"));
            final Field containerSc = findField(ws, "mSurfaceControl"); // WindowContainer field
            hook(setClient).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    WindowManagerParams p = WindowManagerParams.of(chain.getThisObject());
                    if (p != null && p.type == TYPE_BLACKSCREEN_OVERLAY) {
                        Object sc = containerSc.get(chain.getThisObject());
                        if (sc != null) setSkipScreenshot(sc);
                        sBlackScreenWindow =
                                new java.lang.ref.WeakReference<>(chain.getThisObject());
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "skip-screenshot (client surface) failed", t);
                }
                return result;
            });
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setClientSurface hook failed", t);
        }
        // The black screen must not swallow input: PC touches are injected on display 0 and would
        // otherwise hit the overlay first. Treat it like TYPE_SECURE_SYSTEM_OVERLAY, which
        // DisplayPolicy.adjustWindowParamsLw makes NOT_FOCUSABLE|NOT_TOUCHABLE. (OnePlus also
        // marks 2099 a trusted overlay, which only matters for pass-through windows.)
        try {
            Class<?> dp = Class.forName("com.android.server.wm.DisplayPolicy", false, cl);
            Method adjust = dp.getDeclaredMethod("adjustWindowParamsLw",
                    Class.forName("com.android.server.wm.WindowState", false, cl),
                    android.view.WindowManager.LayoutParams.class);
            hook(adjust).intercept(chain -> {
                Object result = chain.proceed();
                android.view.WindowManager.LayoutParams attrs =
                        (android.view.WindowManager.LayoutParams) chain.getArg(1);
                if (attrs != null && attrs.type == TYPE_BLACKSCREEN_OVERLAY) {
                    attrs.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    attrs.flags &= ~android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
                }
                return result;
            });
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "adjustWindowParamsLw hook failed", t);
        }
        try {
            Class<?> im = Class.forName("com.android.server.wm.InputMonitor", false, cl);
            Method trusted = im.getDeclaredMethod("isTrustedOverlay", int.class);
            hook(trusted).intercept(chain -> {
                final int type = (Integer) chain.getArg(0);
                if (type == TYPE_DRAGDROP_OVERLAY || type == TYPE_BLACKSCREEN_OVERLAY) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });
            n++;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "isTrustedOverlay hook failed", t);
        }
        return n;
    }

    // ---------------------------------------------------------------- power key dismisses it
    // OnePlus BlackScreenWindowManager.interceptPowerKey: with the black screen up, a power press
    // removes it instead of turning the screen off. OnePlus delivers the key to the overlay through
    // a custom IWindow method (dispatchBlackScreenKeyEvent) so deviceintegrationservice hides it
    // itself; stock Android has none, so remove the window server-side (== client removeView).

    private static volatile java.lang.ref.WeakReference<Object> sBlackScreenWindow;
    private volatile boolean mConsumePowerUp;
    private android.os.Handler mWmHandler;
    /** WindowManagerPolicy.FLAG_INTERACTIVE */
    private static final int POLICY_FLAG_INTERACTIVE = 0x20000000;

    private int installPowerKeyHook(ClassLoader cl) {
        try {
            Class<?> pwm = Class.forName("com.android.server.policy.PhoneWindowManager", false, cl);
            Method m = pwm.getDeclaredMethod("interceptKeyBeforeQueueing",
                    android.view.KeyEvent.class, int.class);
            hook(m).intercept(chain -> {
                final android.view.KeyEvent ev = (android.view.KeyEvent) chain.getArg(0);
                if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_POWER) {
                    final int policyFlags = (Integer) chain.getArg(1);
                    if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        if (ev.getRepeatCount() == 0
                                && (policyFlags & POLICY_FLAG_INTERACTIVE) != 0
                                && dismissBlackScreen()) {
                            mConsumePowerUp = true;
                            return 0; // consumed: don't turn the screen off
                        }
                        if (mConsumePowerUp) return 0;
                    } else if (ev.getAction() == android.view.KeyEvent.ACTION_UP
                            && mConsumePowerUp) {
                        mConsumePowerUp = false;
                        return 0;
                    }
                }
                return chain.proceed();
            });
            return 1;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "power key hook failed", t);
            return 0;
        }
    }

    /** User of the window's owner. Stock services.jar is R8'd: mOwnerUid may be gone. */
    private static android.os.UserHandle ownerUser(Object win) {
        try {
            final Method m = win.getClass().getDeclaredMethod("getOwningUid");
            m.setAccessible(true);
            return android.os.UserHandle.getUserHandleForUid((Integer) m.invoke(win));
        } catch (Throwable ignored) {
        }
        try {
            final Object session = findField(win.getClass(), "mSession").get(win);
            return android.os.UserHandle.getUserHandleForUid(
                    findField(session.getClass(), "mUid").getInt(session));
        } catch (Throwable ignored) {
        }
        return android.os.UserHandle.getUserHandleForUid(0);
    }

    /** True if a visible black screen was found and its removal was scheduled. */
    private boolean dismissBlackScreen() {
        try {
            final java.lang.ref.WeakReference<Object> ref = sBlackScreenWindow;
            final Object win = ref != null ? ref.get() : null;
            if (win == null) return false;
            final Class<?> wsClass = win.getClass();
            if (findField(wsClass, "mRemoved").getBoolean(win)) return false;
            final Method isVisible = wsClass.getDeclaredMethod("isVisible");
            isVisible.setAccessible(true);
            if (!(Boolean) isVisible.invoke(win)) return false;
            final Object wms = findField(wsClass, "mWmService").get(win);
            final android.view.WindowManager.LayoutParams attrs =
                    (android.view.WindowManager.LayoutParams) findField(wsClass, "mAttrs").get(win);
            if (attrs != null && BROKER_PKG.equals(attrs.packageName)) {
                // V2: the broker owns the black screen (and a gesture monitor); let it hide both.
                final Context ctx = (Context) findField(wms.getClass(), "mContext").get(wms);
                ctx.sendBroadcastAsUser(new android.content.Intent(ACTION_HIDE_BLACK_SCREEN)
                        .setPackage(BROKER_PKG), ownerUser(win));
                log(Log.INFO, TAG, "black screen (broker) dismissed by power key");
                sBlackScreenWindow = null;
                return true;
            }
            final Object session = findField(wsClass, "mSession").get(win);
            final android.os.IInterface client =
                    (android.os.IInterface) findField(wsClass, "mClient").get(win);
            final Method remove = wms.getClass().getDeclaredMethod("removeClientToken",
                    session.getClass(), android.os.IBinder.class);
            remove.setAccessible(true);
            synchronized (this) {
                if (mWmHandler == null) {
                    android.os.HandlerThread t = new android.os.HandlerThread("ltw_blackscreen");
                    t.start();
                    mWmHandler = new android.os.Handler(t.getLooper());
                }
            }
            // Not on the input thread: removeClientToken takes the WM global lock.
            mWmHandler.post(() -> {
                try {
                    remove.invoke(wms, session, client.asBinder());
                    log(Log.INFO, TAG, "black screen dismissed by power key");
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "black screen removal failed", t);
                }
            });
            sBlackScreenWindow = null;
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "dismissBlackScreen failed", t);
            return false;
        }
    }

    private void setSkipScreenshot(Object surfaceControl) throws Exception {
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object tx = txClass.getConstructor().newInstance();
        txClass.getMethod("setSkipScreenshot", scClass, boolean.class)
                .invoke(tx, surfaceControl, true);
        txClass.getMethod("apply").invoke(tx);
        log(Log.INFO, TAG, "black screen surface marked skip-screenshot");
    }

    /** Minimal reflective view of WindowState.mAttrs. */
    private static final class WindowManagerParams {
        int type;

        static WindowManagerParams of(Object windowState) throws Exception {
            Object attrs = findField(windowState.getClass(), "mAttrs").get(windowState);
            if (!(attrs instanceof android.view.WindowManager.LayoutParams)) return null;
            WindowManagerParams p = new WindowManagerParams();
            p.type = ((android.view.WindowManager.LayoutParams) attrs).type;
            return p;
        }
    }

    /**
     * == CrossDeviceManager.isCallerAllowed, evaluated in system_server for the binder caller,
     * plus the broker, which draws the black screen itself in DIS' "V2" mode.
     */
    private static boolean isCrossDeviceCallerAllowed(Context ctx) {
        if (ctx.checkCallingPermission("deviceintegration.permission.MANAGE_CROSS_DEVICE")
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        final int uid = android.os.Binder.getCallingUid();
        final long token = android.os.Binder.clearCallingIdentity();
        try {
            final String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            return pkgs != null && (Arrays.asList(pkgs).contains(DIS_PKG)
                    || Arrays.asList(pkgs).contains(BROKER_PKG));
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    private void registerService(Object ams) {
        if (mServiceRegistered) return;
        try {
            Context ctx = (Context) findField(ams.getClass(), "mContext").get(ams);
            IBinder service = new CrossDeviceService(ctx);
            ServiceManager.addService(SERVICE_NAME, service);
            mServiceRegistered = true;
            log(Log.INFO, TAG, "registered " + SERVICE_NAME);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to register " + SERVICE_NAME
                    + " (check sepolicy.rule)", t);
        }
    }

    private int hookPermissionChecks(ClassLoader cl, String className, String... names) {
        final Set<String> wanted = new HashSet<>(Arrays.asList(names));
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!wanted.contains(m.getName())) continue;
            final Class<?> ret = m.getReturnType();
            if (ret != int.class && ret != boolean.class) continue;
            final boolean bool = ret == boolean.class;
            try {
                hook(m).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!shouldGrant(original, chain.getArgs())) return original;
                    return bool ? Boolean.TRUE : (Object) PackageManager.PERMISSION_GRANTED;
                });
                count++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "cannot hook " + className + "#" + m.getName() + ": " + t);
            }
        }
        log(Log.INFO, TAG, "hooked " + count + " permission check(s) on " + className);
        return count;
    }

    private boolean shouldGrant(Object original, List<Object> args) {
        try {
            if (original instanceof Integer && (Integer) original == PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (original instanceof Boolean && (Boolean) original) return false;
            boolean perm = false;
            boolean broker = false;
            final int brokerAppId = brokerAppId();
            for (Object a : args) {
                if (a instanceof String) {
                    if (EMULATED_PLATFORM_PERMISSIONS.contains(a)) perm = true;
                    else if (BROKER_PKG.equals(a)) broker = true;
                } else if (a instanceof Integer && brokerAppId >= 0
                        && ((Integer) a) % 100000 == brokerAppId) {
                    broker = true;
                }
            }
            return perm && broker;
        } catch (Throwable t) {
            return false;
        }
    }

    private int brokerAppId() {
        if (mBrokerAppId >= 0) return mBrokerAppId;
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager")
                    .invoke(null);
            Object uid;
            try {
                uid = pm.getClass().getMethod("getPackageUid", String.class, long.class, int.class)
                        .invoke(pm, BROKER_PKG, 0L, 0);
            } catch (NoSuchMethodException e) {
                uid = pm.getClass().getMethod("getPackageUid", String.class, int.class, int.class)
                        .invoke(pm, BROKER_PKG, 0, 0);
            }
            if (uid instanceof Integer && (Integer) uid >= 0) {
                mBrokerAppId = ((Integer) uid) % 100000;
                log(Log.INFO, TAG, "broker appId=" + mBrokerAppId);
            }
        } catch (Throwable ignored) {
            // not installed / PM not ready yet: retry on next check
        }
        return mBrokerAppId;
    }

    // ------------------------------------------------------------------ deviceintegrationservice

    private synchronized void installFetcher() {
        if (mFetcherInstalled) return;
        int n = 0;
        try {
            Class<?> ssr = Class.forName("android.app.SystemServiceRegistry");
            for (Method m : ssr.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("getSystemService") || p.length != 2
                        || p[1] != String.class) {
                    continue;
                }
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    if (r == null && SERVICE_NAME.equals(chain.getArg(1))) {
                        r = createManager(chain.getArg(0));
                    }
                    return r;
                });
                n++;
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "fetcher hook failed", t);
        }
        mFetcherInstalled = n > 0;
        log(Log.INFO, TAG, "fetcher hooks in " + DIS_PKG + ": " + n);
    }

    /** Same logic as patch 0001 SystemServiceRegistry.registerCrossDeviceService(). */
    private Object createManager(Object contextImpl) {
        Object m = sManager;
        if (m != null) return m;
        try {
            ClassLoader cl = (ClassLoader) contextImpl.getClass().getMethod("getClassLoader")
                    .invoke(contextImpl);
            if (cl == null) return null;
            m = cl.loadClass(MANAGER_CLASS).getDeclaredConstructor().newInstance();
            sManager = m;
            log(Log.INFO, TAG, "CrossDeviceManager from " + cl);
            return m;
        } catch (Throwable t) {
            log(Log.WARN, TAG, MANAGER_CLASS + " not loadable (is com.microsoft.device-private"
                    + " linked?): " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private int hookAll(ClassLoader cl, String className, String name, XposedInterface.Hooker body) {
        int n = 0;
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                hook(m).intercept(body);
                n++;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + className + "#" + name + ": " + t);
        }
        return n;
    }

    /**
     * Value of a BackgroundActivityStartController BAL_* code on the running build. Stock
     * services.jar is R8-optimized: `static final int` constants are inlined and their fields
     * removed, so fall back to asking the class itself via its public
     * {@code static String balCodeToString(int)}. Throws if neither works, so the hook is not
     * installed rather than installed with a guessed value.
     */
    private static int resolveBalCode(Class<?> bal, String name) throws Exception {
        try {
            return findField(bal, name).getInt(null);
        } catch (NoSuchFieldException inlined) {
            final Method toString = bal.getDeclaredMethod("balCodeToString", int.class);
            toString.setAccessible(true);
            for (int code = 0; code < 256; code++) {
                try {
                    if (name.equals(toString.invoke(null, code))) return code;
                } catch (java.lang.reflect.InvocationTargetException unknownCode) {
                    // balCodeToString throws IllegalArgumentException for unmapped codes
                }
            }
            throw new NoSuchFieldException(name + " not found via balCodeToString");
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name);
    }
}
