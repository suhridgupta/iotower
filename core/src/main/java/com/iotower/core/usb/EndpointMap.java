package com.iotower.core.usb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lookup of endpoints by address, built once on claim (§5). */
public final class EndpointMap {
    private final Map<Integer, EndpointInfo> byAddress = new LinkedHashMap<>();
    private final List<InterfaceInfo> interfaces = new ArrayList<>();
    private int interfaceCount;

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

    /** Number of interfaces on the (first) configuration, from {@code bNumInterfaces}. */
    public int interfaceCount() {
        return interfaceCount;
    }

    public void setInterfaceCount(int interfaceCount) {
        this.interfaceCount = interfaceCount;
    }

    /** Records one interface's class/subclass/protocol triple (§4.1), alt-setting-0 only. */
    public void addInterface(InterfaceInfo info) {
        interfaces.add(info);
    }

    /** Interfaces in interface-number order, one per interface (alt-setting-0 only). */
    public List<InterfaceInfo> interfaces() {
        return Collections.unmodifiableList(interfaces);
    }
}
