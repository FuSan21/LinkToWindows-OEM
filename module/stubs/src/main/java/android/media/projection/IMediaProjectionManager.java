// Compile-only stub (hidden AIDL). Never packaged. Android 14+ 5-arg createProjection.
package android.media.projection;
public interface IMediaProjectionManager extends android.os.IInterface {
    IMediaProjection createProjection(int processUid, String packageName, int type,
            boolean permanentGrant, int displayId) throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IMediaProjectionManager {
        public static IMediaProjectionManager asInterface(android.os.IBinder b) { throw new RuntimeException("stub"); }
    }
}
