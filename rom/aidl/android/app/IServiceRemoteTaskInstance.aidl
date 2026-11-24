// Reconstructed from OnePlus 12 OxygenOS 15 oplus-framework.jar. Method ORDER = transaction codes; do not reorder.
package android.app;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;

/** @hide One streamed app ("remote task"). Implemented in system_server. */
interface IServiceRemoteTaskInstance {
    boolean isTouchRequired();                                              // 1
    int getOrientation();                                                   // 2
    Rect getRemoteTaskBounds();                                             // 3
    boolean isRemoteTaskResizeable();                                       // 4
    String getRemoteTaskInstanceUUID();                                     // 5
    Intent getRemoteTaskIntent();                                           // 6
    String getRemoteTaskInstancePackageId();                                // 7
    boolean isRemoteTaskInstanceShowingSecuredContent();                    // 8
    boolean isRemoteTaskInstanceInFocus();                                  // 9
    Bundle getBundle();                                                     // 10
    oneway void removeRemoteTask();                                         // 11
    oneway void changeRemoteTaskFocus(boolean inFocus);                     // 12
    oneway void notifyRemoteDisplayResolutionChanged(in Rect bounds, int dpi); // 13
    oneway void notifyRemoteDisplayOrientationChanged(int orientation);     // 14
    void notifyRemoteTaskInputEvent();                                      // 15
}
