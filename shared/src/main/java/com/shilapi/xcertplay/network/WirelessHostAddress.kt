package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Prefers a usable IPv4 address for manual APs because Android's SoftAP typically
 * operates an IPv4 DHCP server (e.g. 192.168.43.1) and often does not route or bridge
 * IPv6 link-local neighbor discovery / mDNS packets to connected hotspot clients.
 * Falls back to scoped link-local IPv6 if no IPv4 address is present.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }?.let { return it }

    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return null
}
