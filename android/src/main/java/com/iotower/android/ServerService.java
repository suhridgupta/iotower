package com.iotower.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.iotower.core.net.UsbIpServer;

/**
 * Foreground service that hosts the core {@link UsbIpServer} (§8). Android kills
 * background socket servers and revokes USB access when the process dies, so the
 * server must live here with a persistent notification.
 */
public class ServerService extends Service {
    private static final String CHANNEL_ID = "iotower_server";
    private static final int NOTIFICATION_ID = 1;

    private Thread serverThread;
    private UsbIpServer server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        // TODO(milestone 3): build an AndroidUsbBackend for the claimed device
        // and start the server on a worker thread:
        //   server = new UsbIpServer(backend);
        //   serverThread = new Thread(server, "usbip-server");
        //   serverThread.start();

        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "I/O Tower server", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("I/O Tower")
                .setContentText("USB/IP server running on :3240")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // placeholder icon
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
