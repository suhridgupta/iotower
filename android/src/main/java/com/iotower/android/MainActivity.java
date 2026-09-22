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
 * Minimal launcher UI and the driver for the M6 Host-API spike (architecture
 * §2, §8). Enumerates USB devices, runs the USB permission handshake, and
 * starts/stops {@link HostApiSpike}, which claims every interface (forceClaim)
 * and logs interrupt-IN reports to Logcat.
 *
 * <p>Intentionally thin — platform widgets only, no AndroidX (§13 / Invariant
 * 5). M6 does not use the network or the foreground {@link ServerService}; that
 * is M7. The spike runs from the activity here on purpose (screen-off /
 * service survival is M9).
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.iotower.android.USB_PERMISSION";

    private UsbManager usbManager;
    private final HostApiSpike spike = new HostApiSpike();

    private TextView status;
    private Button toggle;
    private boolean receiverRegistered;

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
        title.setText("I/O Tower — Host-API spike (M6)");
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
        if (spike.isRunning()) {
            spike.stop();
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
            startSpike(device);
        } else {
            setStatus("Requesting permission for " + name(device) + "…");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    pendingIntentFlags());
            usbManager.requestPermission(device, pi);
        }
    }

    private void startSpike(UsbDevice device) {
        String result = spike.start(usbManager, device);
        setStatus(result + "\n\nWatch: adb logcat -s " + HostApiSpike.TAG);
        render();
    }

    private void maybeHandleAttachIntent(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && !spike.isRunning()) {
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
                startSpike(device);
            } else {
                Log.w(HostApiSpike.TAG, "USB permission denied");
                setStatus("USB permission denied for " + name(device));
            }
        }
    };

    private void render() {
        toggle.setText(spike.isRunning() ? "Stop" : "Start");
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
        spike.stop();
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
