/*
 * SPDX-License-Identifier: MIT
 *
 * Port of OnePlus 12 / OxygenOS 15 com.microsoftsdk.crossdeviceservicebroker.CrossDeviceServiceBroker
 * (docs/OEM_INTEGRATION_PLAN.md 8f) to Android 17. Platform-signed system app; the service is guarded
 * by deviceintegration.permission.MANAGE_CROSS_DEVICE, so only privileged/platform callers can bind.
 */
package com.microsoftsdk.crossdeviceservicebroker;

import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Display;

public class CrossDeviceServiceBroker extends Service {
    private static final String TAG = "CrossDeviceServiceBroker";

    // Same literals OnePlus uses (MediaProjectionManager's own constants are @hide).
    private static final int TYPE_SCREEN_CAPTURE = 0;
    private static final String EXTRA_MEDIA_PROJECTION =
            "android.media.projection.extra.EXTRA_MEDIA_PROJECTION";

    private final Object mClipboardLock = new Object();
    private ClipboardManager mClipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener mClipListener;
    private BlackScreen mBlackScreen;

    @Override
    public void onCreate() {
        super.onCreate();
        mBlackScreen = new BlackScreen(this);
    }

    private final ICrossDeviceServiceBroker.Stub mBinder = new ICrossDeviceServiceBroker.Stub() {
        @Override
        public boolean hasPrimaryClip() {
            return mClipboardManager.hasPrimaryClip();
        }

        @Override
        public ClipDescription getPrimaryClipDescription() {
            return mClipboardManager.getPrimaryClipDescription();
        }

        @Override
        public ClipData getPrimaryClip() {
            return mClipboardManager.getPrimaryClip();
        }

        @Override
        public void addClipboardListener(ICrossDeviceClipboardListener listener) {
            synchronized (mClipboardLock) {
                clearClipboardListener();
                mClipListener = () -> {
                    try {
                        listener.onPrimaryClipboardChanged();
                    } catch (RemoteException e) {
                        Log.w(TAG, "clipboard listener died", e);
                    }
                };
                mClipboardManager.addPrimaryClipChangedListener(mClipListener);
            }
        }

        @Override
        public void removeClipboardListener() {
            clearClipboardListener();
        }

        /**
         * No-consent MediaProjection for the caller (deviceintegrationservice). OnePlus calls
         * createProjection(uid, pkg, TYPE_SCREEN_CAPTURE, permanentGrant=false); Android 14+ adds
         * a displayId argument.
         */
        @Override
        public Intent getMediaProjectionIntent(int uid, String packageName) {
            IMediaProjectionManager pm = IMediaProjectionManager.Stub.asInterface(
                    ServiceManager.getService(MEDIA_PROJECTION_SERVICE));
            try {
                IMediaProjection projection = pm.createProjection(uid, packageName,
                        TYPE_SCREEN_CAPTURE, false /* permanentGrant */,
                        Display.DEFAULT_DISPLAY);
                // Equivalent to OnePlus' (hidden) Intent.putExtra(String, IBinder).
                Bundle extras = new Bundle();
                extras.putBinder(EXTRA_MEDIA_PROJECTION, projection.asBinder());
                Intent intent = new Intent();
                intent.putExtras(extras);
                return intent;
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "createProjection failed for uid=" + uid + " pkg=" + packageName, e);
                return null;
            }
        }

        /**
         * Launch with the caller's ActivityOptions bundle. For app streaming the bundle carries
         * deviceintegration:remotetask.{launchOption,uuid,security.token}, which the Remote Task
         * window-manager patch reads in ActivityStarter.
         */
        @Override
        public boolean launch(Intent intent, Bundle options) {
            final long token = Binder.clearCallingIdentity();
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, options);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "launch failed: " + intent, e);
                return false;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        // Codes 8..10: black screen "V2" (see BlackScreen). OnePlus leaves them unimplemented and
        // lacks MONITOR_INPUT, so its deviceintegrationservice draws the legacy overlay instead.
        @Override
        public void showBlackScreen(int titleResId, int descriptionResId, int iconResId) {
            final String[] pkgs = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            mBlackScreen.show(pkgs != null && pkgs.length > 0 ? pkgs[0] : null,
                    titleResId, descriptionResId, iconResId);
        }

        @Override
        public void hideBlackScreen() {
            mBlackScreen.hide();
        }

        @Override
        public void setBlackScreenListener(IBlackScreenListener listener) {
            mBlackScreen.setListener(listener);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        mClipboardManager = getSystemService(ClipboardManager.class);
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        clearClipboardListener();
        // deviceintegrationservice gone: never leave the phone's touches blocked.
        mBlackScreen.hide();
        return super.onUnbind(intent);
    }

    private void clearClipboardListener() {
        synchronized (mClipboardLock) {
            if (mClipListener != null && mClipboardManager != null) {
                mClipboardManager.removePrimaryClipChangedListener(mClipListener);
            }
            mClipListener = null;
        }
    }
}
