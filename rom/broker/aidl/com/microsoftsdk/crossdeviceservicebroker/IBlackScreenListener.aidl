package com.microsoftsdk.crossdeviceservicebroker;

/**
 * Reconstructed from com.microsoft.deviceintegrationservice (2.25044.5.1.0). Method order is fixed
 * by the client's AIDL transaction codes and MUST NOT change.
 *   onPreAdd = 0x1, onAdded = 0x2, onRemoved = 0x3
 */
interface IBlackScreenListener {
    void onPreAdd();
    void onAdded();
    void onRemoved();
}
