// Compile-only stub (hidden AIDL). Never packaged.
package android.app;
public interface IActivityTaskManager extends android.os.IInterface {
    java.util.List<ActivityManager.RunningTaskInfo> getTasks(int maxNum,
            boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId)
            throws android.os.RemoteException;
    boolean removeTask(int taskId) throws android.os.RemoteException;
    void registerTaskStackListener(ITaskStackListener listener) throws android.os.RemoteException;
    void unregisterTaskStackListener(ITaskStackListener listener) throws android.os.RemoteException;
}
