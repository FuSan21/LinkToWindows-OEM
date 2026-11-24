// Reconstructed from OnePlus 12 OxygenOS 15 oplus-framework.jar. Method ORDER = transaction codes; do not reorder.
package android.app;

import android.app.IProximityService;
import android.app.IRemoteTaskCallback;
import android.app.IRfcommOemManager;
import android.app.ITaskChangeListener;
import android.view.InputEvent;
import android.view.Surface;

/** @hide Binder for "cross_device_service" (system_server). Codes 1..18 match OnePlus; 19+ are ours. */
interface ICrossDeviceService {
    void injectInputEvent(in InputEvent event, int mode);                 // 1
    String getTopRunningPackageName();                                      // 2
    void wakeUp(long time);                                                 // 3
    void addRemoteTaskCallback(IRemoteTaskCallback callback);               // 4
    void removeRemoteTaskCallback(IRemoteTaskCallback callback);            // 5
    void updateBackgroundActivityList(in List<String> activityList);        // 6
    void updateSurface(String uuid, in Surface surface);                    // 7
    void updateDisplayInfo(int width, int height, int densityDpi);          // 8
    int getThermalStatus();                                                 // 9
    boolean isResumableFromOverheat();                                      // 10
    IRfcommOemManager getRfcommOemManager();                                // 11
    void setPermissionGranted(int uid);                                     // 12
    boolean getPermissionGranted(int uid);                                  // 13
    void setSecurityToken(String token);                                    // 14
    void registerTaskChangeListener(ITaskChangeListener listener);          // 15
    void unregisterTaskChangeListener(ITaskChangeListener listener);        // 16
    IProximityService getProximityService();                                // 17
    boolean isInstantHotspotCapable();                                      // 18
    // ---- additions (not on OnePlus A15; newer DIS calls CrossDeviceManager.getDeviceTemperature) ----
    float getDeviceTemperature();                                           // 19, NaN if unknown
}
