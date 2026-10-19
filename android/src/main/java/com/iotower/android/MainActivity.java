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
    /** Visible build stamp -- bump on each change to confirm the TV is running new code. */
    private static final String BUILD_ID = "2026-10-19 r6 (m7d + auto-claim + device-select + attach/detach lifecycle)";

    private UsbManager usbManager;

    private TextView status;
    private Button toggle;
    private boolean receiverRegistered;
    /** Whether this activity has asked {@link ServerService} to run. See class doc: no binding. */
    private boolean serverStarted;
    /** Device name ({@code /dev/bus/usb/...}) currently being served; changes on re-enumeration. */
    private String servedDeviceName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate: I/O Tower build " + BUILD_ID);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        }
        IntentFilter usbFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(attachReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(attachReceiver, usbFilter);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (serverStarted) {
            return;
        }
        // If a device is already connected and we already hold permission (e.g.
        // "use by default" was granted once), claim it immediately -- no Start
        // tap, no dialog. This is also the adb-drivable entry point: launching
        // this activity (`am start`) auto-claims whatever is plugged in, so a
        // device that floods the UI with input can be grabbed without fighting it.
        UsbDevice device = pickDevice();
        if (device == null) {
            int n = usbManager.getDeviceList().size();
            if (n > 1) {
                Log.i(TAG, "onResume: " + n + " USB devices present (TV internals) -- "
                        + "not auto-claiming; plug in the device to export so the attach intent names it");
            }
            return;
        }
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "onResume: auto-claiming the only connected device " + name(device));
            startServer(device);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setTextSize(24f);
        title.setText("I/O Tower — build " + BUILD_ID);
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
                int n = usbManager.getDeviceList().size();
                setStatus(n == 0
                        ? "No USB device found. Plug one into the TV."
                        : n + " USB devices present (incl. the TV's own). Unplug and replug the "
                                + "device to export so it is claimed directly.");
                return;
            }
            requestOrStart(device);
        }
        render();
    }

    /**
     * The device to claim when we must choose WITHOUT an attach intent: the sole
     * connected device, or {@code null} if there are none or several. A TV has its
     * own internal USB devices (e.g. a Realtek Bluetooth/Wi-Fi combo), so blindly
     * taking the first would claim the wrong one -- force-claiming the TV's own
     * Bluetooth. When several are present the user plugs in the device to export and
     * the {@code USB_DEVICE_ATTACHED} intent names it exactly (see
     * {@link #maybeHandleAttachIntent}). Device-agnostic: "exactly one" vs
     * "ambiguous", no VID/PID.
     */
    private UsbDevice pickDevice() {
        if (usbManager.getDeviceList().size() != 1) {
            return null;
        }
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
        servedDeviceName = device.getDeviceName();
        setStatus("Serving " + name(device) + " on :3240\n\n"
                + "From the PC: sudo usbip attach -r <tv-ip> -b 1-1");
        render();
    }

    private void stopServer() {
        stopService(new Intent(this, ServerService.class));
        serverStarted = false;
        servedDeviceName = null;
    }

    private void maybeHandleAttachIntent(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        // Re-claim on EVERY attach, including a re-enumeration: a mode-switching
        // wheel (G29 c24f) drops and reappears as a NEW device, and if we don't
        // re-claim it, the kernel input driver re-grabs it and floods the TV UI
        // with navigation events (the "infinite scroll"). ServerService tears down
        // the old session and claims the new device. Ignore only an exact-duplicate
        // intent for the device we are already serving.
        if (serverStarted && device.getDeviceName().equals(servedDeviceName)) {
            return;
        }
        Log.i(TAG, "USB attach: " + name(device) + " (" + device.getDeviceName()
                + ") -> claiming (serverStarted=" + serverStarted + ")");
        requestOrStart(device);
    }

    /**
     * Runtime receiver for USB attach/detach while the app is running (§7/§8 reset
     * recovery). The manifest intent-filter only covers a cold launch; this catches
     * events the running activity would otherwise miss.
     *
     * <ul>
     *   <li><b>attach</b> -- claim the newest device (incl. a re-enumeration),
     *       ignoring only an exact duplicate of what we are already serving;</li>
     *   <li><b>detach</b> of the <em>served</em> device (unplug or a mode-switch
     *       re-enumeration) -- release it cleanly and go idle, so its reappearance
     *       is re-claimed by the attach branch and the PC can re-run
     *       {@code usbip attach} (manual re-attach is the M8 gate; automation is
     *       M10). Detaches of other devices (the TV's internals) are ignored.</li>
     * </ul>
     */
    private final BroadcastReceiver attachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (serverStarted && device.getDeviceName().equals(servedDeviceName)) {
                    return;
                }
                Log.i(TAG, "USB attach (broadcast): " + name(device) + " (" + device.getDeviceName()
                        + ") -> claiming (serverStarted=" + serverStarted + ")");
                requestOrStart(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (serverStarted && device.getDeviceName().equals(servedDeviceName)) {
                    Log.i(TAG, "USB detach (broadcast): served device " + name(device)
                            + " gone -> releasing; will re-claim on reappearance");
                    stopServer();
                    setStatus("Device detached. Will re-claim on replug; re-run `usbip attach` on the PC.");
                    render();
                }
            }
        }
    };

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
            unregisterReceiver(attachReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
