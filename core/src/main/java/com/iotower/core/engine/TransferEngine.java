package com.iotower.core.engine;

import com.iotower.core.usb.UsbBackend;

/**
 * Translates USB/IP URBs to backend calls and correlates asynchronous
 * completions back to their {@code seqnum} (§5, §5.1). Owns the
 * reader → engine → writer flow with a single writer thread so replies never
 * interleave on the wire.
 *
 * <p>TODO(milestone 3): implement submit/ret and unlink/ret, out-of-order
 * completion, and the full concurrency model from §5.1.
 */
public final class TransferEngine {
    private final UsbBackend backend;

    public TransferEngine(UsbBackend backend) {
        this.backend = backend;
    }
}
