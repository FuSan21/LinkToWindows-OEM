/*
 * SPDX-License-Identifier: MIT
 *
 * Link to Windows OEM integration: feature_oem_rfcomm, the Bluetooth side channel.
 *
 * Link to Windows asks deviceintegrationservice (ExtGenericRfCommOemService) to keep an RFCOMM
 * server open under its service UUID; deviceintegrationservice forwards that here with the app's
 * uid. system_server owns the listening socket, so it keeps listening while the apps sleep and
 * across reboots. When the PC connects, the app is woken with
 * deviceintegration.rfcomm.ACTION_BT_SOCKET_CONNECTED and then fetches the socket as two pipes.
 * Same behaviour as OnePlus' com.android.server.deviceexperiences.brokerrfcomm.RfcommOemManager.
 *
 * Shared by the ROM (patch 0001) and the rooted-stock module: public SDK APIs only.
 */
package com.android.server.wm;

import android.app.IRfcommOemManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class LtwRfcommManager extends IRfcommOemManager.Stub {
    private static final String TAG = "LtwRfcomm";

    static final String ACTION_CONNECTED = "deviceintegration.rfcomm.ACTION_BT_SOCKET_CONNECTED";
    static final String ACTION_DISCONNECTED =
            "deviceintegration.rfcomm.ACTION_BT_SOCKET_DISCONNECTED";
    static final String EXTRA_MAC = "deviceintegration:rfcomm.extra.MACADDRESS";
    static final String EXTRA_NAME = "deviceintegration:rfcomm.extra.NAME";
    /** Receivers must hold it (normal permission; the broker declares it). */
    static final String PERMISSION_EVENT = "deviceintegration.permission.BLUETOOTH_TRANSPORT_EVENT";
    private static final String SERVICE_NAME = "RfcommOemManager"; // SDP record name, as OnePlus

    /** uuid + app uid -> server (null while Bluetooth is off). Guarded by this. */
    private final Map<Key, Server> mServers = new HashMap<>();
    /** remote MAC -> connected socket. Guarded by this. */
    private final Map<String, Session> mSessions = new HashMap<>();

    private final Context mContext;
    private final Handler mHandler;
    private final AtomicFile mStore =
            new AtomicFile(new File("/data/system/ltw_rfcomm_servers.txt"));
    private boolean mStarted;

    LtwRfcommManager(Context context) {
        mContext = context;
        final HandlerThread t = new HandlerThread(TAG);
        t.start();
        mHandler = new Handler(t.getLooper());
        // Bluetooth may not be up yet this early in boot: restore saved servers once it is,
        // and on the first call at the latest.
        try {
            final IntentFilter bt = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            bt.addAction(Intent.ACTION_BOOT_COMPLETED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                        ensureStarted();
                        return;
                    }
                    switch (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        case BluetoothAdapter.STATE_ON: onBluetoothOn(); break;
                        case BluetoothAdapter.STATE_OFF: onBluetoothOff(); break;
                    }
                }
            }, bt, null, mHandler);
            final IntentFilter pkg = new IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            pkg.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    onPackageRemoved(intent.getIntExtra(Intent.EXTRA_UID, -1));
                }
            }, pkg, null, mHandler);
        } catch (RuntimeException e) {
            Slog.w(TAG, "receivers not registered; saved servers restore on first use", e);
        }
    }

    // ------------------------------------------------------------------ IRfcommOemManager

    @Override
    public boolean enableRfComm(int appUid, String uuid) {
        enforceCaller();
        final UUID id = parse(uuid);
        if (id == null) return false;
        // OnePlus requires the caller (deviceintegrationservice) to hold BLUETOOTH_CONNECT.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Slog.w(TAG, "caller lacks BLUETOOTH_CONNECT");
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            ensureStarted();
            synchronized (this) {
                return enableLocked(new Key(id, appUid), true);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean disableRfComm(int appUid, String uuid) {
        enforceCaller();
        final UUID id = parse(uuid);
        if (id == null) return false;
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final Key key = new Key(id, appUid);
                if (!mServers.containsKey(key)) return false;
                close(mServers.remove(key));
                save();
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public ParcelFileDescriptor getInputParcelFileDescriptor(int appUid, String macAddress) {
        enforceCaller();
        synchronized (this) {
            final Session s = sessionFor(appUid, macAddress);
            return s == null ? null : s.pipeIn();
        }
    }

    @Override
    public ParcelFileDescriptor getOutputParcelFileDescriptor(int appUid, String macAddress) {
        enforceCaller();
        synchronized (this) {
            final Session s = sessionFor(appUid, macAddress);
            return s == null ? null : s.pipeOut();
        }
    }

    @Override
    public boolean closeAllConnections(int appUid) {
        enforceCaller();
        synchronized (this) {
            for (Iterator<Session> it = mSessions.values().iterator(); it.hasNext(); ) {
                final Session s = it.next();
                if (s.appUid == appUid) {
                    s.close();
                    it.remove();
                }
            }
            return true;
        }
    }

    @Override
    public boolean closeClientConnections(int appUid, String macAddress) {
        enforceCaller();
        synchronized (this) {
            final Session s = sessionFor(appUid, macAddress);
            if (s == null) return false;
            s.close();
            mSessions.remove(macAddress);
            return true;
        }
    }

    // ------------------------------------------------------------------ servers

    /** The binder is handed out by cross_device_service only; still, check who calls. */
    private void enforceCaller() {
        if (Binder.getCallingUid() == Process.SYSTEM_UID) return;
        mContext.enforceCallingPermission(CrossDeviceService.PERMISSION_MANAGE_CROSS_DEVICE,
                "IRfcommOemManager");
    }

    private void ensureStarted() {
        synchronized (this) {
            if (mStarted) return;
            mStarted = true;
            for (Key key : load()) {
                if (isInstalledUid(key.appUid)) enableLocked(key, false);
            }
            save();
        }
    }

    /** Opens (or records, while Bluetooth is off) the server for key. */
    private boolean enableLocked(Key key, boolean persist) {
        if (mServers.get(key) != null) return true;
        // One app per UUID: a UUID still held by another installed app is refused; a stale one
        // (app gone) is taken over.
        for (Iterator<Map.Entry<Key, Server>> it = mServers.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Key, Server> e = it.next();
            if (e.getKey().uuid.equals(key.uuid) && e.getKey().appUid != key.appUid) {
                if (isInstalledUid(e.getKey().appUid)) {
                    Slog.w(TAG, "UUID " + key.uuid + " is used by uid " + e.getKey().appUid);
                    return false;
                }
                close(e.getValue());
                it.remove();
            }
        }
        final BluetoothAdapter adapter = adapter();
        Server server = null;
        if (adapter != null && adapter.isEnabled()) {
            server = Server.listen(this, adapter, key);
            if (server == null) return false;
        }
        mServers.put(key, server);
        if (persist) save();
        Slog.i(TAG, "RFCOMM server " + key + (server != null ? " listening" : " (Bluetooth off)"));
        return true;
    }

    private void onBluetoothOn() {
        ensureStarted();
        synchronized (this) {
            final BluetoothAdapter adapter = adapter();
            if (adapter == null) return;
            for (Map.Entry<Key, Server> e : mServers.entrySet()) {
                if (e.getValue() == null) e.setValue(Server.listen(this, adapter, e.getKey()));
            }
        }
    }

    private void onBluetoothOff() {
        synchronized (this) {
            for (Map.Entry<Key, Server> e : mServers.entrySet()) {
                close(e.getValue());
                e.setValue(null);
            }
            for (Session s : mSessions.values()) s.close();
            mSessions.clear();
        }
    }

    private void onPackageRemoved(int uid) {
        if (uid < 0) return;
        synchronized (this) {
            boolean changed = false;
            for (Iterator<Map.Entry<Key, Server>> it = mServers.entrySet().iterator();
                    it.hasNext(); ) {
                final Map.Entry<Key, Server> e = it.next();
                if (e.getKey().appUid == uid) {
                    close(e.getValue());
                    it.remove();
                    changed = true;
                }
            }
            if (changed) save();
        }
    }

    /** Called on a server thread for every accepted connection. */
    void onAccepted(Key key, BluetoothSocket socket) {
        final String mac = socket.getRemoteDevice().getAddress();
        synchronized (this) {
            final Session old = mSessions.remove(mac);
            if (old != null) old.close(); // a reconnect replaces the previous session
            final Session s = Session.open(this, socket, key.appUid);
            if (s == null) return;
            mSessions.put(mac, s);
        }
        notifyApp(key.appUid, socket, ACTION_CONNECTED);
    }

    /** Called when a session's socket ends. */
    void onSessionEnded(Session session) {
        synchronized (this) {
            if (mSessions.get(session.mac) != session) return;
            mSessions.remove(session.mac);
        }
        notifyApp(session.appUid, session.socket, ACTION_DISCONNECTED);
    }

    private void notifyApp(int appUid, BluetoothSocket socket, String action) {
        final String[] pkgs = mContext.getPackageManager().getPackagesForUid(appUid);
        if (pkgs == null || pkgs.length == 0) return;
        final Intent intent = new Intent(action)
                .setPackage(pkgs[0])
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(EXTRA_MAC, socket.getRemoteDevice().getAddress());
        String name = null;
        try {
            name = socket.getRemoteDevice().getName();
        } catch (SecurityException ignored) {
            // system_server holds BLUETOOTH_CONNECT; keep going without the name otherwise
        }
        intent.putExtra(EXTRA_NAME, name);
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(appUid),
                    PERMISSION_EVENT);
            Slog.i(TAG, action + " -> " + pkgs[0]);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Session sessionFor(int appUid, String mac) {
        final Session s = mac == null ? null : mSessions.get(mac);
        return s != null && s.appUid == appUid ? s : null;
    }

    private BluetoothAdapter adapter() {
        final BluetoothManager bm = mContext.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    private boolean isInstalledUid(int uid) {
        return mContext.getPackageManager().getNameForUid(uid) != null;
    }

    private static void close(Server s) {
        if (s != null) s.close();
    }

    private static UUID parse(String uuid) {
        try {
            return uuid == null ? null : UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ persistence

    /** One "uuid appUid" per line. */
    private List<Key> load() {
        final List<Key> keys = new ArrayList<>();
        if (!mStore.getBaseFile().exists()) return keys;
        try {
            for (String line : new String(mStore.readFully(), StandardCharsets.UTF_8).split("\n")) {
                final String[] f = line.trim().split(" ");
                if (f.length != 2) continue;
                final UUID id = parse(f[0]);
                if (id != null) keys.add(new Key(id, Integer.parseInt(f[1])));
            }
        } catch (IOException | NumberFormatException e) {
            Slog.w(TAG, "cannot read " + mStore.getBaseFile(), e);
        }
        return keys;
    }

    private void save() {
        final StringBuilder sb = new StringBuilder();
        for (Key k : mServers.keySet()) sb.append(k.uuid).append(' ').append(k.appUid).append('\n');
        FileOutputStream out = null;
        try {
            out = mStore.startWrite();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            mStore.finishWrite(out);
        } catch (IOException e) {
            if (out != null) mStore.failWrite(out);
            Slog.w(TAG, "cannot write " + mStore.getBaseFile(), e);
        }
    }

    // ------------------------------------------------------------------ types

    static final class Key {
        final UUID uuid;
        final int appUid;

        Key(UUID uuid, int appUid) {
            this.uuid = uuid;
            this.appUid = appUid;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).uuid.equals(uuid) && ((Key) o).appUid == appUid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid, appUid);
        }

        @Override
        public String toString() {
            return uuid + "/" + appUid;
        }
    }

    /** A listening RFCOMM server socket and its accept thread. */
    static final class Server extends Thread {
        private final LtwRfcommManager mOwner;
        private final Key mKey;
        private final BluetoothServerSocket mSocket;
        private volatile boolean mClosed;

        private Server(LtwRfcommManager owner, Key key, BluetoothServerSocket socket) {
            super(TAG + "-accept-" + key.appUid);
            mOwner = owner;
            mKey = key;
            mSocket = socket;
        }

        static Server listen(LtwRfcommManager owner, BluetoothAdapter adapter, Key key) {
            try {
                final Server s = new Server(owner, key,
                        adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, key.uuid));
                s.start();
                return s;
            } catch (IOException | SecurityException e) {
                Slog.w(TAG, "listen failed for " + key, e);
                return null;
            }
        }

        @Override
        public void run() {
            while (!mClosed) {
                final BluetoothSocket socket;
                try {
                    socket = mSocket.accept();
                } catch (IOException e) {
                    if (!mClosed) Slog.w(TAG, "accept failed for " + mKey + "; server stops", e);
                    return;
                }
                Slog.i(TAG, "accepted " + socket.getRemoteDevice().getAddress() + " on " + mKey);
                mOwner.onAccepted(mKey, socket);
            }
        }

        void close() {
            mClosed = true;
            try {
                mSocket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** A connected socket, handed to the app as one read pipe and one write pipe. */
    static final class Session {
        final LtwRfcommManager owner;
        final BluetoothSocket socket;
        final String mac;
        final int appUid;
        private final InputStream mIn;
        private final OutputStream mOut;
        private boolean mInGiven;
        private boolean mOutGiven;
        private volatile boolean mClosed;

        private Session(LtwRfcommManager owner, BluetoothSocket socket, int appUid,
                InputStream in, OutputStream out) {
            this.owner = owner;
            this.socket = socket;
            this.mac = socket.getRemoteDevice().getAddress();
            this.appUid = appUid;
            mIn = in;
            mOut = out;
        }

        static Session open(LtwRfcommManager owner, BluetoothSocket socket, int appUid) {
            try {
                return new Session(owner, socket, appUid, socket.getInputStream(),
                        socket.getOutputStream());
            } catch (IOException e) {
                Slog.w(TAG, "no streams for accepted socket", e);
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
                return null;
            }
        }

        /** Socket -> app: the app reads the returned end. Once per session. */
        ParcelFileDescriptor pipeIn() {
            if (mInGiven) return null;
            try {
                final ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
                mInGiven = true;
                pump("in", mIn, new ParcelFileDescriptor.AutoCloseOutputStream(p[1]));
                return p[0];
            } catch (IOException e) {
                return null;
            }
        }

        /** App -> socket: the app writes the returned end. Once per session. */
        ParcelFileDescriptor pipeOut() {
            if (mOutGiven) return null;
            try {
                final ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
                mOutGiven = true;
                pump("out", new ParcelFileDescriptor.AutoCloseInputStream(p[0]), mOut);
                return p[1];
            } catch (IOException e) {
                return null;
            }
        }

        private void pump(String dir, InputStream from, OutputStream to) {
            new Thread(() -> {
                final byte[] buf = new byte[4096];
                try {
                    int n;
                    while (!mClosed && (n = from.read(buf)) > 0) {
                        to.write(buf, 0, n);
                        to.flush();
                    }
                } catch (IOException ignored) {
                    // the socket or the app's pipe end closed
                } finally {
                    try {
                        from.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                    try {
                        to.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                    close();
                    owner.onSessionEnded(this);
                }
            }, TAG + "-" + dir).start();
        }

        void close() {
            mClosed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
