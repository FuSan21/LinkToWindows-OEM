// Compile-only stub (hidden AIDL). Never packaged.
package android.os;
public interface IPowerManager extends IInterface {
    void wakeUp(long time, int reason, String details, String opPackageName) throws RemoteException;
    abstract class Stub extends Binder implements IPowerManager {
        public static IPowerManager asInterface(IBinder b) { throw new RuntimeException("stub"); }
    }
}
