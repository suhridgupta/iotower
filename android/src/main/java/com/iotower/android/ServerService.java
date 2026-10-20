package com.iotower.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.iotower.core.net.UsbIpServer;
import com.iotower.core.usb.DeviceInfo;

import java.util.Locale;

/**
 * Foreground service that hosts the core {@link UsbIpServer} (§8). Android kills
 * background socket servers and revokes USB access when the process dies, so the
 * server must live here with a persistent notification.
 *
 * <p>Receives the permission-granted {@link UsbDevice} from {@link MainActivity}
 * via {@code UsbManager.EXTRA_DEVICE}. All blocking USB work — opening + claiming
 * the device and running the accept loop — happens on a dedicated worker thread,
 * NEVER on the main thread (open()/claim block, and doing that in
 * {@code onStartCommand} trips a foreground-service ANR). A re-delivered start
 * command replaces the running session (teardown + re-claim), so a device that
 * re-enumerates (e.g. a G29 mode switch) is re-claimed rather than left to the
 * kernel input driver; the full reset-recovery lifecycle is M8/M9.
 */
public class ServerService extends Service {
    private static final String TAG = "IoTowerService";
    private static final String CHANNEL_ID = "iotower_server";
    private static final int NOTIFICATION_ID = 1;

    private Thread serverThread;
    private volatile UsbIpServer server;
    private volatile AndroidUsbBackend backend;
    private volatile boolean stopping;
    /** Serializes session start/teardown so a rapid re-attach can't race a running session. */
    private final Object lifecycleLock = new Object();
    /** M9 (§8): partial wake lock held for the serving window. Guarded by {@link #lifecycleLock}. */
    private PowerManager.WakeLock wakeLock;

    /**
     * M9: true after a gone-signal teardown until the next session starts. Lets
     * {@link MainActivity} tell "still serving this device" from "gone, waiting for
     * it to return" so a re-attach under the same device name isn't ignored as a
     * duplicate. Same-process static; cleared in {@link #startSession} / onDestroy.
     */
    private static volatile boolean waitingForDevice;

    static boolean isWaitingForDevice() {
        return waitingForDevice;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // M9 (§8): the type must match the manifest; API 34+ enforces it.
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"));
        }

        final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        final UsbDevice device = intent != null
                ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) : null;
        if (device == null) {
            updateNotification("No device supplied");
            stopSelf();
            return START_NOT_STICKY;
        }

        // A re-delivered start command (re-enumeration / device swap) must REPLACE
        // the current session, not stack a second one on the same :3240 socket.
        // Do the teardown + the blocking open()/claim on a starter thread (never the
        // main thread -> foreground-service ANR), serialized by lifecycleLock so a
        // rapid re-attach can't race a half-built session.
        new Thread(() -> startSession(usbManager, device), "iotower-session-starter").start();
        return START_STICKY;
    }

    /** Tear down any running session, then open + claim {@code device} and serve it. */
    private void startSession(UsbManager usbManager, UsbDevice device) {
        synchronized (lifecycleLock) {
            teardownLocked();
            if (stopping) {
                return; // service is being destroyed
            }
            waitingForDevice = false;
            AndroidUsbBackend b = AndroidUsbBackend.open(usbManager, device);
            if (b == null) {
                updateNotification("Failed to open/claim device");
                return;
            }
            UsbIpServer s = new UsbIpServer(b);
            backend = b;
            server = s;
            acquireWakeLockLocked();
            // M9 (§7/§8): consume the backend's own gone signal, in addition to the
            // DETACH broadcast. Fired on a reader thread -> hand off immediately.
            b.setGoneListener(() -> new Thread(() -> onBackendGone(b), "iotower-gone-teardown").start());
            Thread t = new Thread(s::run, "iotower-server"); // the serve loop; blocks until stop()
            serverThread = t;
            t.start();
            updateNotification("Serving " + describe(b) + " on :3240");
        }
    }

    /**
     * M9: the backend {@code b} detected its device gone. Run the same serialized
     * teardown as every other path, then show a waiting state. Does NOT re-acquire:
     * the device's reappearance arrives as ACTION_USB_DEVICE_ATTACHED -> a fresh start
     * command -> a fresh backend (so deviceGone/consecErr reset only via a new
     * instance, per M7d). The {@code backend != b} check makes a stale signal (session
     * already replaced by a re-attach, or already torn down by the DETACH path) a no-op.
     */
    private void onBackendGone(AndroidUsbBackend b) {
        synchronized (lifecycleLock) {
            if (stopping || backend != b) {
                Log.i(TAG, "gone signal ignored (session already replaced/torn down)");
                return;
            }
            Log.w(TAG, "backend reports device gone -> tearing down session; waiting for device");
            teardownLocked();
            waitingForDevice = true;
            updateNotification("Device gone - waiting for device");
        }
    }

    private void acquireWakeLockLocked() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            Log.w(TAG, "no PowerManager; serving without a wake lock");
            return;
        }
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iotower:serving");
        wl.setReferenceCounted(false);
        wl.acquire();
        wakeLock = wl;
        Log.i(TAG, "wake lock acquired (PARTIAL_WAKE_LOCK)");
    }

    private void releaseWakeLockLocked() {
        PowerManager.WakeLock wl = wakeLock;
        wakeLock = null;
        if (wl != null && wl.isHeld()) {
            wl.release();
            Log.i(TAG, "wake lock released");
        }
    }

    /**
     * Stop the current server, release the device, and clear the session fields.
     * Order matters: stop the server first so no transfer is mid-flight when the
     * backend closes. Caller must hold {@link #lifecycleLock}.
     */
    private void teardownLocked() {
        UsbIpServer s = server;
        if (s != null) {
            s.stop();
        }
        Thread t = serverThread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AndroidUsbBackend b = backend;
        if (b != null) {
            b.close(); // releases the interfaces so the next claim can take over
        }
        server = null;
        serverThread = null;
        backend = null;
        releaseWakeLockLocked(); // M9: serving window over
    }

    private static String describe(AndroidUsbBackend backend) {
        DeviceInfo info = backend.deviceInfo();
        if (info == null) {
            return "device";
        }
        return String.format(Locale.US, "%04X:%04X", info.idVendor, info.idProduct);
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "I/O Tower server", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("I/O Tower")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder icon
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        synchronized (lifecycleLock) {
            teardownLocked();
            waitingForDevice = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
