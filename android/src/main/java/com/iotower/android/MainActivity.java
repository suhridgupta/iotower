package com.iotower.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Minimal launcher UI and the driver for the M7 server (architecture §2, §8).
 * Enumerates USB devices, runs the USB permission handshake, and starts/stops
 * {@link ServerService}, which claims every interface (forceClaim), builds an
 * {@link AndroidUsbBackend}, and runs the device-agnostic {@code UsbIpServer}
 * so a remote Linux PC can {@code usbip attach} to the device plugged into the
 * TV.
 *
 * <p>Intentionally thin — platform widgets only, no AndroidX (§13 / Invariant
 * 5). This activity only starts/stops the foreground service; it does not hold
 * the backend or the server itself, so it does not track a dying service (M9).
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.iotower.android.USB_PERMISSION";
    private static final String TAG = "IoTower";

    private UsbManager usbManager;

    private TextView status;
    private Button toggle;
    private boolean receiverRegistered;
    /** Whether this activity has asked {@link ServerService} to run. See class doc: no binding. */
    private boolean serverStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        }
        receiverRegistered = true;

        render();
        // If we were launched by a device-attach intent, jump straight in.
        maybeHandleAttachIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeHandleAttachIntent(intent);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setTextSize(24f);
        title.setText("I/O Tower — USB/IP server (M7)");
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(18f);
        status.setPadding(0, 32, 0, 32);
        root.addView(status);

        toggle = new Button(this);
        toggle.setText("Start");
        toggle.setOnClickListener(v -> onToggle());
        toggle.requestFocus(); // D-pad focus for the TV remote
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START;
        root.addView(toggle, lp);

        return root;
    }

    private void onToggle() {
        if (serverStarted) {
            stopServer();
            setStatus("Stopped. Device released.");
        } else {
            UsbDevice device = pickDevice();
            if (device == null) {
                setStatus("No USB device found. Plug one into the TV.");
                return;
            }
            requestOrStart(device);
        }
        render();
    }

    /** First device the Host API exposes (device-agnostic — no VID/PID filter). */
    private UsbDevice pickDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            return device;
        }
        return null;
    }

    private void requestOrStart(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            startServer(device);
        } else {
            setStatus("Requesting permission for " + name(device) + "…");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    pendingIntentFlags());
            usbManager.requestPermission(device, pi);
        }
    }

    private void startServer(UsbDevice device) {
        Intent intent = new Intent(this, ServerService.class)
                .putExtra(UsbManager.EXTRA_DEVICE, device);
        startForegroundService(intent);
        serverStarted = true;
        setStatus("Serving " + name(device) + " on :3240\n\n"
                + "From the PC: sudo usbip attach -r <tv-ip> -b 1-1");
        render();
    }

    private void stopServer() {
        stopService(new Intent(this, ServerService.class));
        serverStarted = false;
    }

    private void maybeHandleAttachIntent(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && !serverStarted) {
            requestOrStart(device);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                startServer(device);
            } else {
                Log.w(TAG, "USB permission denied");
                setStatus("USB permission denied for " + name(device));
            }
        }
    };

    private void render() {
        toggle.setText(serverStarted ? "Stop" : "Start");
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private static String name(UsbDevice device) {
        if (device == null) return "device";
        return String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId());
    }

    private static int pendingIntentFlags() {
        // FLAG_IMMUTABLE is required from API 31 (§8: permission handshake).
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
