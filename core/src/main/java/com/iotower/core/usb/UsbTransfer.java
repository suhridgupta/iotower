package com.iotower.core.usb;

import java.util.concurrent.CompletableFuture;

/**
 * A submitted transfer and its eventual completion. The engine keys these by
 * USB/IP seqnum (a {@code ConcurrentHashMap}) so out-of-order completions match
 * back to the right reply (§5.1).
 */
public final class UsbTransfer {
    /** Backend-specific handle (e.g. the Android {@code UsbRequest}) for cancellation. */
    public final Object handle;

    public final CompletableFuture<Result> completion = new CompletableFuture<>();

    public UsbTransfer(Object handle) {
        this.handle = handle;
    }

    /** Completion payload: status (0 ok, else {@code -errno}) and transferred bytes. */
    public static final class Result {
        public final int status;
        public final byte[] data;
        public final int actualLength;

        public Result(int status, byte[] data, int actualLength) {
            this.status = status;
            this.data = data;
            this.actualLength = actualLength;
        }
    }
}
