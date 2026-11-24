// Reconstructed from OnePlus 12 OxygenOS 15 oplus-framework.jar. Method ORDER = transaction codes; do not reorder.
package android.app;

import android.os.ParcelFileDescriptor;

/** @hide feature_oem_rfcomm is reported unsupported for now; kept for API completeness. */
interface IRfcommOemManager {
    boolean enableRfComm(int channel, String address);                      // 1
    boolean disableRfComm(int channel, String address);                     // 2
    ParcelFileDescriptor getInputParcelFileDescriptor(int channel, String address);  // 3
    ParcelFileDescriptor getOutputParcelFileDescriptor(int channel, String address); // 4
    boolean closeAllConnections(int channel);                               // 5
    boolean closeClientConnections(int channel, String address);            // 6
}
