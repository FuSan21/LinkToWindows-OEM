// Reconstructed from OnePlus 12 OxygenOS 15 oplus-framework.jar. Method ORDER = transaction codes; do not reorder.
package android.app;

import android.os.ParcelFileDescriptor;

/**
 * @hide feature_oem_rfcomm: Bluetooth RFCOMM servers that system_server keeps listening on behalf
 * of an app (Link to Windows' Bluetooth side channel). deviceintegrationservice forwards calls
 * with the calling app's uid. Implemented by com.android.server.wm.LtwRfcommManager.
 */
interface IRfcommOemManager {
    boolean enableRfComm(int appUid, String uuid);                                     // 1
    boolean disableRfComm(int appUid, String uuid);                                    // 2
    ParcelFileDescriptor getInputParcelFileDescriptor(int appUid, String macAddress);  // 3
    ParcelFileDescriptor getOutputParcelFileDescriptor(int appUid, String macAddress); // 4
    boolean closeAllConnections(int appUid);                                           // 5
    boolean closeClientConnections(int appUid, String macAddress);                     // 6
}
