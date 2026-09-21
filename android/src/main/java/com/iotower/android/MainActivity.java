package com.iotower.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Minimal launcher UI: shows status and (eventually) starts/stops the foreground
 * server service. Intentionally thin — no AndroidX, platform theme only (§13:
 * no third-party libraries).
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setPadding(48, 48, 48, 48);
        tv.setTextSize(20f);
        tv.setText("I/O Tower\n\nUSB/IP server. Start the service to export a claimed "
                + "USB device on TCP 3240.");
        setContentView(tv);

        // TODO(milestone 1): enumerate USB devices, request permission, claim
        // every interface (forceClaim), build the endpoint map, offer the device.
        // TODO: wire a button to start/stop ServerService via startForegroundService().
    }
}
