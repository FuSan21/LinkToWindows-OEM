/*
 * SPDX-License-Identifier: MIT
 */
package com.android.server.wm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Link to Windows OEM integration: window policy shared by the ROM build (called from the
 * frameworks/base patches) and the rooted-stock module (called from LtwEmu's hooks). Only public
 * and @hide SDK APIs, no window-manager internals, so both builds compile the same file.
 *
 * Counterpart of OnePlus PhoneWindowManagerExtImpl / BlackScreenWindowManager.
 */
public final class LtwWindowPolicy {
    public static final String DIS_PACKAGE = "com.microsoft.deviceintegrationservice";
    public static final String BROKER_PACKAGE = "com.microsoftsdk.crossdeviceservicebroker";
    public static final String PERMISSION_MANAGE_CROSS_DEVICE =
            "deviceintegration.permission.MANAGE_CROSS_DEVICE";
    /** Broker BlackScreen.ACTION_HIDE; its receiver requires the sender to hold MONITOR_INPUT. */
    public static final String ACTION_HIDE_BLACK_SCREEN =
            "com.microsoftsdk.crossdeviceservicebroker.action.HIDE_BLACK_SCREEN";

    /**
     * OnePlus window types. deviceintegrationservice reads
     * WindowManager.LayoutParams.TYPE_SYSTEM_{DRAGDROP,BLACKSCREEN}_OVERLAY reflectively and falls
     * back to these values.
     */
    public static final int TYPE_DRAGDROP_OVERLAY = 2098;
    public static final int TYPE_BLACKSCREEN_OVERLAY = 2099;
    /** Layer of TYPE_SECURE_SYSTEM_OVERLAY: above the status and navigation bars. */
    public static final int OVERLAY_LAYER = 33;

    private static boolean sConsumePowerUp;

    private LtwWindowPolicy() {
    }

    public static boolean isLtwOverlay(int type) {
        return type == TYPE_DRAGDROP_OVERLAY || type == TYPE_BLACKSCREEN_OVERLAY;
    }

    public static boolean isBlackScreen(int type) {
        return type == TYPE_BLACKSCREEN_OVERLAY;
    }

    /**
     * PhoneWindowManager.checkAddPermission for the binder caller (OnePlus
     * overrideCheckAddPermission): == CrossDeviceManager.isCallerAllowed, plus the broker, which
     * draws the black screen itself in deviceintegrationservice's "V2" mode.
     */
    public static boolean isCrossDeviceCaller(Context context) {
        if (context.checkCallingPermission(PERMISSION_MANAGE_CROSS_DEVICE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        final int uid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            final String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return false;
            for (String p : pkgs) {
                if (DIS_PACKAGE.equals(p) || BROKER_PACKAGE.equals(p)) return true;
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * DisplayPolicy.adjustWindowParamsLw: the black screen must not swallow the PC's injected
     * touches (treated like TYPE_SECURE_SYSTEM_OVERLAY).
     */
    public static void adjustWindowParams(WindowManager.LayoutParams attrs) {
        if (attrs != null && isBlackScreen(attrs.type)) {
            attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            attrs.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
    }

    /** Dismisses the black screen if one is up; true if it did. */
    public interface BlackScreenDismisser {
        boolean dismiss();
    }

    /**
     * PhoneWindowManager.interceptKeyBeforeQueueing (input thread, no WM lock): while the black
     * screen is up, a power press dismisses it instead of turning the screen off. The matching
     * key-up (and repeats) are consumed too.
     *
     * @return true if the event was consumed.
     */
    public static synchronized boolean interceptPowerKey(KeyEvent event, boolean interactive,
            BlackScreenDismisser dismisser) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_POWER) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            final boolean consume = sConsumePowerUp;
            sConsumePowerUp = false;
            return consume;
        }
        if (event.getRepeatCount() > 0) return sConsumePowerUp;
        if (interactive && dismisser.dismiss()) {
            sConsumePowerUp = true;
            return true;
        }
        return false;
    }

    /**
     * Black screen "V2": the broker owns the window and a gesture monitor (which blocks the
     * phone's own touches); ask it to hide both.
     */
    public static void hideBrokerBlackScreen(Context context, UserHandle user) {
        context.sendBroadcastAsUser(
                new Intent(ACTION_HIDE_BLACK_SCREEN).setPackage(BROKER_PACKAGE), user);
    }
}
