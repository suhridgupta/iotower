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
 * <p>Receives the permission-granted {@link UsbDevice} from {@link
 * MainActivity} via {@code UsbManager.EXTRA_DEVICE}, builds an {@link
 * AndroidUsbBackend}, and runs the device-agnostic {@link UsbIpServer} on a
 * worker thread (its accept loop blocks). One device, one session (M7 scope) —
 * device reset / re-enumeration recovery is M8/M9, not here.
 */
public class ServerService extends Service {
    private static final String CHANNEL_ID = "iotower_server";
    private static final int NOTIFICATION_ID = 1;

    private Thread serverThread;
    private UsbIpServer server;
    private AndroidUsbBackend backend;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"));

        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice device = intent != null
                ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) : null;

        backend = AndroidUsbBackend.open(usbManager, device);
        if (backend == null) {
            // Invariant 6 / §8: open+claim can fail (permission lost, race). Do
            // not leave a dead foreground service running.
            updateNotification("Failed to open/claim device");
            stopSelf();
            return START_NOT_STICKY;
        }

        server = new UsbIpServer(backend);
        serverThread = new Thread(server, "usbip-server");
        serverThread.start();

        updateNotification("Serving " + describe(backend) + " on :3240");
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
        // Order matters (PRD §2): stop the server first so no transfer is
        // mid-flight when the backend closes.
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (backend != null) {
            backend.close();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
