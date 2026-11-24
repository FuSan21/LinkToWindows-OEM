// Reconstructed from OnePlus 12 OxygenOS 15 oplus-framework.jar. Method ORDER = transaction codes; do not reorder.
package android.app;

/** @hide feature_proximity is reported unsupported; kept for API completeness. */
interface IProximityService {
    void enableTrigger(String id);                                          // 1
    void disableTrigger();                                                  // 2
    boolean isTriggerEnabled();                                             // 3
    void resetTriggerExpiration();                                          // 4
}
