package com.iotower.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Launcher UI and the driver for the server (architecture §2, §8). Enumerates
 * USB devices, runs the USB permission handshake, and starts/stops
 * {@link ServerService}, which claims every interface (forceClaim), builds an
 * {@link AndroidUsbBackend}, and runs the device-agnostic {@code UsbIpServer}
 * so a remote Linux PC can {@code usbip attach} to the device plugged into the
 * TV.
 *
 * <p>Platform widgets + {@code android.graphics} only — no AndroidX, no layout
 * XML (§13 / Invariant 5). The UI is a 10-foot launcher: a wordmark, a status
 * card with a colored state dot, and one D-pad-focusable Start/Stop control.
 * This activity only starts/stops the foreground service; it does not hold the
 * backend or the server itself, so it does not track a dying service (M9). The
 * UI is presentation only and stays device-agnostic — a device is shown as its
 * {@code VID:PID}, never a name table.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.iotower.android.USB_PERMISSION";
    private static final String TAG = "IoTower";
    /** Visible build stamp -- bump on each change to confirm the TV is running new code. */
    private static final String BUILD_ID = "2026-10-20 r7 (m10 companion + finalized TV UI)";

    // -- Tron-ish 10-foot palette (the name is from TRON's I/O Tower). -------- //
    private static final int BG        = Color.parseColor("#0A0E14"); // near-black
    private static final int SURFACE   = Color.parseColor("#121821"); // card
    private static final int BORDER    = Color.parseColor("#1E2A36");
    private static final int ACCENT    = Color.parseColor("#33E1ED"); // cyan
    private static final int TEXT      = Color.parseColor("#E6EDF3");
    private static final int TEXT_DIM  = Color.parseColor("#8B98A5");
    private static final int STATE_IDLE  = Color.parseColor("#55626E"); // gray
    private static final int STATE_GO    = Color.parseColor("#3DDC97"); // green
    private static final int STATE_WAIT  = Color.parseColor("#F0B429"); // amber
    private static final int STATE_ERR   = Color.parseColor("#FF5C5C"); // red

    private enum State { IDLE, REQUESTING, SERVING, WAITING, ERROR }

    private UsbManager usbManager;

    private TextView status;
    private Button toggle;
    private TextView stateLabel;
    private View dot;
    private TextView hint;
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

    // ----------------------------------------------------------------------- //
    // UI construction (presentation only).
    // ----------------------------------------------------------------------- //
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(dp(40), dp(36), dp(40), dp(36));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(760),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardLp);

        // Wordmark + tagline.
        TextView wordmark = new TextView(this);
        wordmark.setText("I/O TOWER");
        wordmark.setTextColor(TEXT);
        wordmark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        wordmark.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        wordmark.setLetterSpacing(0.18f);
        card.addView(wordmark);

        TextView tagline = new TextView(this);
        tagline.setText("No-root USB/IP server");
        tagline.setTextColor(ACCENT);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tagline.setLetterSpacing(0.05f);
        tagline.setPadding(0, dp(4), 0, dp(24));
        card.addView(tagline);

        card.addView(divider());

        // State row: colored dot + label.
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateRow.setPadding(0, dp(20), 0, dp(10));

        dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.setMargins(0, 0, dp(12), 0);
        dot.setLayoutParams(dotLp);
        dot.setBackground(dotDrawable(STATE_IDLE));
        stateRow.addView(dot);

        stateLabel = new TextView(this);
        stateLabel.setTextColor(TEXT);
        stateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        stateLabel.setLetterSpacing(0.08f);
        stateLabel.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        stateLabel.setText("IDLE");
        stateRow.addView(stateLabel);
        card.addView(stateRow);

        // Detail status line.
        status = new TextView(this);
        status.setTextColor(TEXT_DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        status.setLineSpacing(dp(4), 1f);
        status.setPadding(0, 0, 0, dp(18));
        card.addView(status);

        // Monospace attach-command hint (shown only while serving).
        hint = new TextView(this);
        hint.setTextColor(ACCENT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setBackground(hintBackground());
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        hint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.bottomMargin = dp(24);
        hint.setLayoutParams(hintLp);
        card.addView(hint);

        // Start/Stop control.
        toggle = new Button(this);
        toggle.setText("Start");
        toggle.setAllCaps(true);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        toggle.setLetterSpacing(0.08f);
        toggle.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        toggle.setBackground(buttonBackground());
        toggle.setTextColor(buttonTextColors());
        toggle.setPadding(dp(48), dp(16), dp(48), dp(16));
        toggle.setStateListAnimator(null); // drop DeviceDefault elevation jump
        toggle.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.gravity = Gravity.START;
        card.addView(toggle, toggleLp);

        root.addView(card);

        // Dim build-stamp footer.
        TextView footer = new TextView(this);
        footer.setText("build " + BUILD_ID);
        footer.setTextColor(BORDER);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footer.setPadding(0, dp(20), 0, 0);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(footer);

        toggle.requestFocus(); // D-pad focus for the TV remote
        return root;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        v.setLayoutParams(lp);
        v.setBackgroundColor(BORDER);
        return v;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), BORDER);
        return g;
    }

    private GradientDrawable hintBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), BORDER);
        return g;
    }

    private GradientDrawable dotDrawable(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Accent-filled when focused/pressed (clear TV D-pad focus), outlined otherwise. */
    private StateListDrawable buttonBackground() {
        GradientDrawable active = new GradientDrawable();
        active.setColor(ACCENT);
        active.setCornerRadius(dp(10));

        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.TRANSPARENT);
        idle.setCornerRadius(dp(10));
        idle.setStroke(dp(2), ACCENT);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, active);
        sl.addState(new int[]{android.R.attr.state_pressed}, active);
        sl.addState(new int[]{}, idle);
        return sl;
    }

    private ColorStateList buttonTextColors() {
        int[][] states = {
                {android.R.attr.state_focused},
                {android.R.attr.state_pressed},
                {}
        };
        int[] colors = {BG, BG, ACCENT};
        return new ColorStateList(states, colors);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private void onToggle() {
        if (serverStarted) {
            stopServer();
            setState(State.IDLE);
            setStatus("Stopped. Device released.");
        } else {
            UsbDevice device = pickDevice();
            if (device == null) {
                int n = usbManager.getDeviceList().size();
                setState(State.IDLE);
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
            setState(State.REQUESTING);
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
        setState(State.SERVING);
        setStatus("Serving " + name(device) + " on :3240");
        setHint("sudo usbip attach -r " + ipForHint() + " -b 1-1");
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
        if (serverStarted && device.getDeviceName().equals(servedDeviceName)
                && !ServerService.isWaitingForDevice()) {
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
                if (serverStarted && device.getDeviceName().equals(servedDeviceName)
                        && !ServerService.isWaitingForDevice()) {
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
                    setState(State.WAITING);
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
                setState(State.ERROR);
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

    /** Update the colored dot + label; show the attach hint only while serving. */
    private void setState(State state) {
        runOnUiThread(() -> {
            int color;
            String label;
            switch (state) {
                case SERVING:    color = STATE_GO;   label = "SERVING";    break;
                case REQUESTING: color = STATE_WAIT; label = "REQUESTING"; break;
                case WAITING:    color = STATE_WAIT; label = "WAITING";    break;
                case ERROR:      color = STATE_ERR;  label = "ERROR";      break;
                case IDLE:
                default:         color = STATE_IDLE; label = "IDLE";       break;
            }
            dot.setBackground(dotDrawable(color));
            stateLabel.setText(label);
            if (state != State.SERVING) {
                hint.setVisibility(View.GONE);
            }
        });
    }

    private void setHint(String command) {
        runOnUiThread(() -> {
            hint.setText(command);
            hint.setVisibility(View.VISIBLE);
        });
    }

    /** The TV's own LAN IP for the attach hint, or a placeholder if unknown. */
    private String ipForHint() {
        String ip = localIpAddress();
        return ip != null ? ip : "<tv-ip>";
    }

    /**
     * First non-loopback, site-local IPv4 of the TV (for the on-screen attach
     * command). Needs no permission. Returns {@code null} on any failure, so the
     * UI falls back to the {@code <tv-ip>} placeholder -- never worse than before.
     */
    private static String localIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localIpAddress failed: " + e);
        }
        return null;
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
