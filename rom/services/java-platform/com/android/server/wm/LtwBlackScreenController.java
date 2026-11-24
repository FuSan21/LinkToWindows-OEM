/*
 * SPDX-License-Identifier: MIT
 */
package com.android.server.wm;

import android.os.UserHandle;
import android.view.KeyEvent;

import java.lang.ref.WeakReference;

/**
 * Link to Windows OEM integration (ROM build only: uses window-manager internals; the rooted-stock
 * module does the same through reflection): deviceintegrationservice's black screen, window type
 * 2099.
 *
 * Counterpart of OnePlus' com.android.server.wm.BlackScreenWindowManager: while the black screen
 * is up, a power press dismisses it instead of turning the screen off. OnePlus hands the key to the
 * overlay through a custom IWindow method so deviceintegrationservice hides it itself; stock
 * Android has none, so the window is removed server-side (equivalent to the client's removeView).
 * If the broker owns the window (black screen V2), the broker is asked to hide it instead, so it
 * also stops its gesture monitor.
 */
public final class LtwBlackScreenController {
    private static WeakReference<WindowState> sWindow;

    private LtwBlackScreenController() {
    }

    /**
     * WindowState.setClientSurface, WM lock held. Android 17 window surfaces are client-created and
     * bypass WindowStateAnimator's SKIP_SCREENSHOT flag: mark the window's container instead, so
     * the black screen stays off the PC mirror.
     */
    static void onClientSurface(WindowState win) {
        if (!LtwWindowPolicy.isBlackScreen(win.mAttrs.type)) return;
        win.getPendingTransaction().setSkipScreenshot(win.mSurfaceControl, true);
        synchronized (LtwBlackScreenController.class) {
            sWindow = new WeakReference<>(win);
        }
    }

    /** PhoneWindowManager.interceptKeyBeforeQueueing. @return true if consumed. */
    public static boolean interceptPowerKey(KeyEvent event, boolean interactive) {
        return LtwWindowPolicy.interceptPowerKey(event, interactive,
                LtwBlackScreenController::dismiss);
    }

    private static boolean dismiss() {
        final WindowState win;
        synchronized (LtwBlackScreenController.class) {
            win = sWindow != null ? sWindow.get() : null;
            sWindow = null;
        }
        if (win == null || win.mRemoved) return false;
        final WindowManagerService wms = win.mWmService;
        if (LtwWindowPolicy.BROKER_PACKAGE.equals(win.getOwningPackage())) {
            final UserHandle user = UserHandle.getUserHandleForUid(win.getOwningUid());
            wms.mH.post(() -> LtwWindowPolicy.hideBrokerBlackScreen(wms.mContext, user));
            return true;
        }
        wms.mH.post(() -> {
            synchronized (wms.mGlobalLock) {
                if (!win.mRemoved && win.isVisible()) {
                    wms.removeClientToken(win.mSession, win.mClient.asBinder());
                }
            }
        });
        return true;
    }
}
