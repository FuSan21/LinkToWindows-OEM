// Reconstructed from OnePlus 12 OxygenOS 15 oplus-framework.jar. Method ORDER = transaction codes; do not reorder.
package android.app;

import android.app.IServiceRemoteTaskInstance;
import android.content.Intent;

/** @hide Implemented by deviceintegrationservice; called by system_server. */
oneway interface IRemoteTaskCallback {
    void activateRemoteTask(IServiceRemoteTaskInstance instance);                                    // 1
    void notifyRemoteTaskRemoved(String uuid, int reason);                                           // 2
    void notifyDisplaySwitched(IServiceRemoteTaskInstance instance, in Intent intent, int flag);     // 3
    void notifyDeviceAvailabilityStateChanged(int state);                                            // 4
    void notifyRemoteTaskEmptyUUIDetected(in Intent intent);                                         // 5
    void notifyRemoteTaskCreationFailed(String uuid, int reason);                                    // 6
    void notifyRemoteShowingSecuredContentChanged(IServiceRemoteTaskInstance instance, boolean secured); // 7
    void notifyDuplicateRemoteTask(in Intent intent, String needFocusUuid, String interceptUuid, int reason); // 8
    void notifyClientDied();                                                                         // 9
}
