// Compile-only stub (hidden). Never packaged.
package android.app;
public abstract class TaskStackListener extends ITaskStackListener.Stub {
    public TaskStackListener() { }
    public void onTaskStackChanged() throws android.os.RemoteException { }
    public void onTaskRemoved(int taskId) throws android.os.RemoteException { }
    public void onTaskDisplayChanged(int taskId, int newDisplayId) throws android.os.RemoteException { }
}
