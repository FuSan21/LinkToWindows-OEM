package com.microsoftsdk.crossdeviceservicebroker;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.os.Bundle;
import com.microsoftsdk.crossdeviceservicebroker.IBlackScreenListener;
import com.microsoftsdk.crossdeviceservicebroker.ICrossDeviceClipboardListener;

/**
 * The privileged broker that com.microsoft.deviceintegrationservice binds to for all screen-mirror,
 * app-stream launch and clipboard operations. On OnePlus/OPPO it is the platform-signed
 * /system/app/CrossDeviceServiceBroker APK, which implements codes 1..7 only (no black screen).
 *
 * Reconstructed verbatim from the client's AIDL. The METHOD DECLARATION ORDER below is load-bearing:
 * AIDL assigns transaction codes by order (FIRST_CALL_TRANSACTION + index), and the client's codes
 * are fixed in its APK. Order here reproduces codes 0x1..0xa exactly:
 *   hasPrimaryClip=1, getPrimaryClipDescription=2, getPrimaryClip=3, addClipboardListener=4,
 *   getMediaProjectionIntent=5, launch=6, removeClipboardListener=7, showBlackScreen=8,
 *   hideBlackScreen=9, setBlackScreenListener=10.
 */
interface ICrossDeviceServiceBroker {
    boolean hasPrimaryClip();
    ClipDescription getPrimaryClipDescription();
    ClipData getPrimaryClip();
    void addClipboardListener(ICrossDeviceClipboardListener listener);
    // First arg is the client's UID (DIS passes Process.myUid()), as on OnePlus — not a display id.
    Intent getMediaProjectionIntent(int uid, String packageName);
    boolean launch(in Intent intent, in Bundle options);
    void removeClipboardListener();
    // Black screen "V2": deviceintegrationservice uses these instead of drawing its own window
    // 2099 when the broker holds android.permission.MONITOR_INPUT. The ints are resource ids in
    // the CALLER's package (R.string.black_screen_title / _description, R.drawable.ic_blackscreen_icon).
    void showBlackScreen(int titleResId, int descriptionResId, int iconResId);
    void hideBlackScreen();
    void setBlackScreenListener(IBlackScreenListener listener);
}
