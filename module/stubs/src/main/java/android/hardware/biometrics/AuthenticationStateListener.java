// Compile-only stub (hidden AIDL). Never packaged; the real class is in framework.jar.
package android.hardware.biometrics;

import android.hardware.biometrics.events.*;

public interface AuthenticationStateListener extends android.os.IInterface {
    void onAuthenticationAcquired(AuthenticationAcquiredInfo authInfo) throws android.os.RemoteException;
    void onAuthenticationError(AuthenticationErrorInfo authInfo) throws android.os.RemoteException;
    void onAuthenticationFailed(AuthenticationFailedInfo authInfo) throws android.os.RemoteException;
    void onAuthenticationHelp(AuthenticationHelpInfo authInfo) throws android.os.RemoteException;
    void onAuthenticationStarted(AuthenticationStartedInfo authInfo) throws android.os.RemoteException;
    void onAuthenticationStopped(AuthenticationStoppedInfo authInfo) throws android.os.RemoteException;
    void onAuthenticationSucceeded(AuthenticationSucceededInfo authInfo) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements AuthenticationStateListener {
        public Stub() { throw new RuntimeException("stub"); }
        @Override public android.os.IBinder asBinder() { throw new RuntimeException("stub"); }
    }
}
