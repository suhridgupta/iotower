package com.iotower.core.usb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lookup of endpoints by address, built once on claim (§5). */
public final class EndpointMap {
    private final Map<Integer, EndpointInfo> byAddress = new LinkedHashMap<>();

    public void add(EndpointInfo ep) {
        byAddress.put(ep.address, ep);
    }

    public EndpointInfo get(int address) {
        return byAddress.get(address);
    }

    public Map<Integer, EndpointInfo> all() {
        return Collections.unmodifiableMap(byAddress);
    }

    public int size() {
        return byAddress.size();
    }
}
