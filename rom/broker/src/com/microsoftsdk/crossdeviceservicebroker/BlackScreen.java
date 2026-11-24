/*
 * SPDX-License-Identifier: MIT
 */
package com.microsoftsdk.crossdeviceservicebroker;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Black screen "V2" for deviceintegrationservice (DIS).
 *
 * DIS has two black-screen implementations (DependencyModule.provideBlackScreenController):
 * if the broker holds android.permission.MONITOR_INPUT it calls the broker's
 * showBlackScreen/hideBlackScreen/setBlackScreenListener; otherwise ("legacy", what OnePlus ships)
 * it adds its own window type 2099 and tags injected PC events with MotionEvent flag 0x1000, which
 * OnePlus' native input dispatcher presumably uses to let them through.
 *
 * Here the broker draws the black screen and keeps the phone's own touches away from the apps
 * underneath, while the PC keeps full control:
 * <ul>
 *   <li>The window is type 2099, so the framework treats it like DIS' legacy overlay: kept out of
 *       the mirror (skip-screenshot), trusted, and NOT_TOUCHABLE (patches 0002/0003).</li>
 *   <li>A gesture monitor (spy window, MONITOR_INPUT) sees every pointer stream on the display.
 *       Streams from a physical device are pilfered, so apps get only DOWN + CANCEL; injected
 *       streams (the PC's, deviceId -1 / virtual) are left alone.</li>
 *   <li>Same UX as DIS' own overlay: a touch brings the faded content back and restarts the
 *       60 s burn-in timer; a vertical swipe over 150 px removes the black screen. The power key
 *       removes it too (system_server sends {@link #ACTION_HIDE}, patch 0009).</li>
 * </ul>
 */
final class BlackScreen {
    private static final String TAG = "LtwBlackScreen";

    /** Sent by system_server (power key). Senders must hold MONITOR_INPUT (signature). */
    static final String ACTION_HIDE =
            "com.microsoftsdk.crossdeviceservicebroker.action.HIDE_BLACK_SCREEN";
    private static final String SENDER_PERMISSION = "android.permission.MONITOR_INPUT";

    /** OnePlus TYPE_SYSTEM_BLACKSCREEN_OVERLAY; see patch 0002. */
    private static final int TYPE_BLACKSCREEN_OVERLAY = 2099;
    /** Same values as DIS' legacy overlay (blackscreen.a / blackscreen.m). */
    private static final long BURN_IN_MS = 60_000L;
    private static final float DISMISS_SWIPE_PX = 150f;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindowManager;
    private final KeyguardManager mKeyguardManager;
    private final SparseBooleanArray mPhysicalDevice = new SparseBooleanArray();

    // main thread only
    private IBlackScreenListener mListener;
    private View mWindow;
    private View mContent;
    private InputMonitor mMonitor;
    private InputEventReceiver mReceiver;
    private BroadcastReceiver mHideReceiver;
    private float mDownY;
    private final Runnable mBurnIn = () -> {
        if (mContent != null) mContent.setAlpha(0f);
    };

    BlackScreen(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
    }

    void setListener(IBlackScreenListener listener) {
        mHandler.post(() -> mListener = listener);
    }

    void show(String callerPackage, int titleRes, int descriptionRes, int iconRes) {
        mHandler.post(() -> showInternal(callerPackage, titleRes, descriptionRes, iconRes));
    }

    void hide() {
        mHandler.post(this::hideInternal);
    }

    // ------------------------------------------------------------------------------ main thread

    private void showInternal(String callerPackage, int titleRes, int descriptionRes, int iconRes) {
        if (mWindow != null) {
            reveal();
            return;
        }
        notifyListener(0);
        final View window = buildView(callerPackage, titleRes, descriptionRes, iconRes);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_BLACKSCREEN_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("com.microsoftsdk.crossdeviceservicebroker.blackscreen");
        try {
            mWindowManager.addView(window, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot add black screen window", e);
            return;
        }
        mWindow = window;
        startTouchBlocking();
        registerAuthListener();
        registerHideReceiver();
        reveal();
        Log.i(TAG, "black screen shown (touch blocking=" + (mMonitor != null) + ")");
        notifyListener(1);
    }

    private void hideInternal() {
        if (mWindow == null) return;
        stopTouchBlocking();
        unregisterAuthListener();
        if (mHideReceiver != null) {
            try {
                mContext.unregisterReceiver(mHideReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            mHideReceiver = null;
        }
        mHandler.removeCallbacks(mBurnIn);
        try {
            mWindowManager.removeViewImmediate(mWindow);
        } catch (RuntimeException e) {
            Log.w(TAG, "black screen window already gone", e);
        }
        mWindow = null;
        mContent = null;
        Log.i(TAG, "black screen hidden");
        notifyListener(2);
    }

    /** Content visible again + restart the burn-in timer (DIS: setContentAlpha(1) + timer). */
    private void reveal() {
        if (mContent != null) mContent.setAlpha(1f);
        mHandler.removeCallbacks(mBurnIn);
        mHandler.postDelayed(mBurnIn, BURN_IN_MS);
    }

    private void startTouchBlocking() {
        try {
            final InputManager im = mContext.getSystemService(InputManager.class);
            // @hide (and @Deprecated in favour of the token variant on InputManagerGlobal), still
            // the simplest way for an app holding MONITOR_INPUT; reflection keeps the emulation
            // build on the public SDK.
            mMonitor = (InputMonitor) InputManager.class
                    .getMethod("monitorGestureInput", String.class, int.class)
                    .invoke(im, "ltw-blackscreen", Display.DEFAULT_DISPLAY);
            mReceiver = new Receiver(mMonitor);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Without it the black screen still works, phone touches just reach the apps below
            // (same as DIS' legacy overlay on stock Android).
            Log.e(TAG, "gesture monitor unavailable; phone touches will pass through", e);
            stopTouchBlocking();
        }
    }

    private void stopTouchBlocking() {
        if (mReceiver != null) {
            mReceiver.dispose();
            mReceiver = null;
        }
        if (mMonitor != null) {
            mMonitor.dispose();
            mMonitor = null;
        }
    }

    private void registerHideReceiver() {
        mHideReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "hide requested by system (power key)");
                hideInternal();
            }
        };
        mContext.registerReceiver(mHideReceiver, new IntentFilter(ACTION_HIDE),
                SENDER_PERMISSION, mHandler, Context.RECEIVER_EXPORTED);
    }

    // ------------------------------------------------------------- under-display fingerprint

    /** A fingerprint authentication is running (any reason; keyguard is handled separately). */
    private volatile boolean mFingerprintAuthActive;
    private Object mFingerprintService; // IFingerprintService
    private final android.hardware.biometrics.AuthenticationStateListener mAuthListener =
            new android.hardware.biometrics.AuthenticationStateListener.Stub() {
                @Override
                public void onAuthenticationStarted(
                        android.hardware.biometrics.events.AuthenticationStartedInfo info) {
                    mFingerprintAuthActive = true;
                    Log.i(TAG, "fingerprint auth started, reason=" + info.getRequestReason());
                }

                @Override
                public void onAuthenticationStopped(
                        android.hardware.biometrics.events.AuthenticationStoppedInfo info) {
                    mFingerprintAuthActive = false;
                    Log.i(TAG, "fingerprint auth stopped");
                }

                @Override
                public void onAuthenticationAcquired(
                        android.hardware.biometrics.events.AuthenticationAcquiredInfo info) {
                }

                @Override
                public void onAuthenticationError(
                        android.hardware.biometrics.events.AuthenticationErrorInfo info) {
                }

                @Override
                public void onAuthenticationFailed(
                        android.hardware.biometrics.events.AuthenticationFailedInfo info) {
                }

                @Override
                public void onAuthenticationHelp(
                        android.hardware.biometrics.events.AuthenticationHelpInfo info) {
                }

                @Override
                public void onAuthenticationSucceeded(
                        android.hardware.biometrics.events.AuthenticationSucceededInfo info) {
                }
            };

    /** IFingerprintService.registerAuthenticationStateListener (@hide, USE_BIOMETRIC_INTERNAL). */
    private void registerAuthListener() {
        try {
            final android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "fingerprint");
            if (b == null) return;
            final Object svc = Class.forName("android.hardware.fingerprint.IFingerprintService$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
            svc.getClass().getMethod("registerAuthenticationStateListener",
                    android.hardware.biometrics.AuthenticationStateListener.class)
                    .invoke(svc, mAuthListener);
            mFingerprintService = svc;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot listen for fingerprint authentication", e);
        }
    }

    private void unregisterAuthListener() {
        final Object svc = mFingerprintService;
        mFingerprintService = null;
        mFingerprintAuthActive = false;
        if (svc == null) return;
        try {
            svc.getClass().getMethod("unregisterAuthenticationStateListener",
                    android.hardware.biometrics.AuthenticationStateListener.class)
                    .invoke(svc, mAuthListener);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "unregisterAuthenticationStateListener failed", e);
        }
    }

    /** UDFPS centres/radii in the sensor's coordinate space (natural orientation, max mode). */
    private float[] mUdfps; // x, y, r triples; empty = none
    private int mUdfpsNativeShortSide;

    /**
     * Load the UDFPS location(s) via the @hide FingerprintManager.getSensorPropertiesInternal
     * (USE_BIOMETRIC_INTERNAL, signature: granted to the platform-signed broker).
     */
    private void loadFingerprintSensors() {
        if (mUdfps != null) return;
        final java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        try {
            final Object fm = mContext.getSystemService(
                    android.hardware.fingerprint.FingerprintManager.class);
            final java.util.List<?> props = fm == null ? java.util.Collections.emptyList()
                    : (java.util.List<?>) fm.getClass().getMethod("getSensorPropertiesInternal")
                            .invoke(fm);
            for (Object p : props) {
                if (!(Boolean) p.getClass().getMethod("isAnyUdfpsType").invoke(p)) continue;
                final Object loc = p.getClass().getMethod("getLocation").invoke(p);
                final Class<?> lc = loc.getClass();
                out.add((float) lc.getField("sensorLocationX").getInt(loc));
                out.add((float) lc.getField("sensorLocationY").getInt(loc));
                out.add((float) lc.getField("sensorRadius").getInt(loc));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "fingerprint sensor location unavailable", e);
        }
        mUdfps = new float[out.size()];
        for (int i = 0; i < mUdfps.length; i++) mUdfps[i] = out.get(i);
        // Sensor coordinates refer to the panel's largest mode (SystemUI: UdfpsOverlayParams).
        int shortSide = 0;
        final Display d = mContext.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (d != null) {
            for (Display.Mode m : d.getSupportedModes()) {
                shortSide = Math.max(shortSide,
                        Math.min(m.getPhysicalWidth(), m.getPhysicalHeight()));
            }
        }
        mUdfpsNativeShortSide = shortSide;
        Log.i(TAG, "UDFPS sensors: " + java.util.Arrays.toString(mUdfps)
                + " nativeShortSide=" + shortSide);
    }

    /** Touch (display coordinates, current rotation) on or near a UDFPS sensor? */
    private boolean onFingerprintSensor(float x, float y) {
        loadFingerprintSensors();
        if (mUdfps.length == 0) return false;
        final Display d = mContext.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return false;
        final android.graphics.Point size = new android.graphics.Point();
        d.getRealSize(size);
        final int rotation = d.getRotation();
        final boolean swapped = rotation == android.view.Surface.ROTATION_90
                || rotation == android.view.Surface.ROTATION_270;
        final float natW = swapped ? size.y : size.x;
        final float natH = swapped ? size.x : size.y;
        // current (rotated) -> natural orientation
        final float nx;
        final float ny;
        switch (rotation) {
            case android.view.Surface.ROTATION_90:  nx = natW - y; ny = x; break;
            case android.view.Surface.ROTATION_180: nx = natW - x; ny = natH - y; break;
            case android.view.Surface.ROTATION_270: nx = y; ny = natH - x; break;
            default: nx = x; ny = y; break;
        }
        final float scale = mUdfpsNativeShortSide > 0
                ? Math.min(natW, natH) / mUdfpsNativeShortSide : 1f;
        for (int i = 0; i + 2 < mUdfps.length; i += 3) {
            final float dx = nx - mUdfps[i] * scale;
            final float dy = ny - mUdfps[i + 1] * scale;
            final float r = mUdfps[i + 2] * scale * 1.5f; // margin for an imprecise finger
            if (dx * dx + dy * dy <= r * r) return true;
        }
        return false;
    }

    /** Physical pointer device? Injected events carry deviceId -1 (virtual). */
    private boolean isPhysical(int deviceId) {
        final int idx = mPhysicalDevice.indexOfKey(deviceId);
        if (idx >= 0) return mPhysicalDevice.valueAt(idx);
        final InputDevice dev = InputDevice.getDevice(deviceId);
        final boolean physical = dev != null && !dev.isVirtual();
        mPhysicalDevice.put(deviceId, physical);
        return physical;
    }

    private final class Receiver extends InputEventReceiver {
        private final InputMonitor mOwner;

        Receiver(InputMonitor monitor) {
            super(monitor.getInputChannel(), Looper.getMainLooper());
            mOwner = monitor;
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            if (event instanceof MotionEvent && isPhysical(event.getDeviceId())) {
                final MotionEvent ev = (MotionEvent) event;
                handled = true;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // Steal the whole stream: apps underneath get CANCEL right after DOWN.
                        // Not while locked: the PC can't use the phone then anyway, and the
                        // keyguard / under-display fingerprint (SystemUI) must get the touches.
                        // Same for a touch on the under-display fingerprint sensor while a
                        // fingerprint authentication runs (BiometricPrompt in an app): SystemUI's
                        // UDFPS overlay needs it. Otherwise the sensor area is blocked too.
                        if (!mKeyguardManager.isKeyguardLocked()
                                && !(mFingerprintAuthActive
                                        && onFingerprintSensor(ev.getRawX(), ev.getRawY()))) {
                            try {
                                mOwner.pilferPointers();
                            } catch (RuntimeException e) {
                                Log.w(TAG, "pilferPointers failed", e);
                            }
                        }
                        mDownY = ev.getRawY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(ev.getRawY() - mDownY) > DISMISS_SWIPE_PX) {
                            mHandler.post(BlackScreen.this::hideInternal);
                        } else {
                            reveal();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        reveal();
                        break;
                    default:
                        break;
                }
            }
            finishInputEvent(event, handled);
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    /** 0 = onPreAdd, 1 = onAdded, 2 = onRemoved. */
    private void notifyListener(int what) {
        final IBlackScreenListener l = mListener;
        if (l == null) return;
        try {
            if (what == 0) l.onPreAdd();
            else if (what == 1) l.onAdded();
            else l.onRemoved();
        } catch (RemoteException e) {
            Log.w(TAG, "black screen listener died", e);
            mListener = null;
        }
    }

    private View buildView(String callerPackage, int titleRes, int descriptionRes, int iconRes) {
        Resources res = null;
        try {
            if (callerPackage != null) {
                res = mContext.createPackageContext(callerPackage, 0).getResources();
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot load resources of " + callerPackage, e);
        }
        final FrameLayout root = new FrameLayout(mContext);
        root.setBackgroundColor(Color.BLACK);

        final LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        final int pad = dp(32);
        content.setPadding(pad, pad, pad, pad);

        final Drawable icon = res != null ? safeDrawable(res, iconRes) : null;
        if (icon != null) {
            final ImageView image = new ImageView(mContext);
            image.setImageDrawable(icon);
            final LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(56), dp(56));
            ilp.bottomMargin = dp(24);
            content.addView(image, ilp);
        }
        content.addView(text(safeText(res, titleRes, "Your phone screen is shown on your PC"),
                20, Color.WHITE));
        final TextView desc = text(safeText(res, descriptionRes,
                "Swipe up or press the power button to use your phone."), 14, 0xB3FFFFFF);
        desc.setPadding(0, dp(12), 0, 0);
        content.addView(desc);

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        mContent = content;
        return root;
    }

    private TextView text(CharSequence s, int sp, int color) {
        final TextView tv = new TextView(mContext);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        return tv;
    }

    private static CharSequence safeText(Resources res, int id, String fallback) {
        try {
            if (res != null && id != 0) return res.getText(id);
        } catch (Resources.NotFoundException ignored) {
        }
        return fallback;
    }

    private static Drawable safeDrawable(Resources res, int id) {
        try {
            if (id != 0) return res.getDrawable(id, null);
        } catch (Resources.NotFoundException ignored) {
        }
        return null;
    }

    private int dp(int v) {
        return Math.round(v * mContext.getResources().getDisplayMetrics().density);
    }
}
