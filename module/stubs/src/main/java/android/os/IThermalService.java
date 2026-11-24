// Compile-only stub (hidden AIDL). Never packaged.
package android.os;
public interface IThermalService extends IInterface {
    Temperature[] getCurrentTemperaturesWithType(int type) throws RemoteException;
    abstract class Stub extends Binder implements IThermalService {
        public static IThermalService asInterface(IBinder b) { throw new RuntimeException("stub"); }
    }
}
