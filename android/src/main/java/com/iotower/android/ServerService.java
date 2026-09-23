package com.iotower.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;

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
 * {@code onStartCommand} trips a foreground-service ANR). One device, one session
 * (M7 scope); reset/re-enumeration recovery is M8/M9.
 */
public class ServerService extends Service {
    private static final String CHANNEL_ID = "iotower_server";
    private static final int NOTIFICATION_ID = 1;

    private Thread serverThread;
    private volatile UsbIpServer server;
    private volatile AndroidUsbBackend backend;
    private volatile boolean stopping;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"));

        final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        final UsbDevice device = intent != null
                ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) : null;

        // All blocking USB work off the main thread (open()/claim block).
        serverThread = new Thread(() -> {
            AndroidUsbBackend b = AndroidUsbBackend.open(usbManager, device);
            if (b == null || stopping) {
                if (b != null) {
                    b.close();
                }
                updateNotification("Failed to open/claim device");
                stopSelf();
                return;
            }
            backend = b;
            UsbIpServer s = new UsbIpServer(b);
            server = s;
            updateNotification("Serving " + describe(b) + " on :3240");
            s.run(); // blocks until stop()
        }, "iotower-server");
        serverThread.start();

        return START_STICKY;
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
        // Order matters: stop the server first so no transfer is mid-flight when
        // the backend closes.
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
            b.close();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
