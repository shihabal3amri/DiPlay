package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessHostAddressTest {
    @Test fun manualApPrefersIpv4EvenWhenLinkLocalComesFirst() {
        val ipv4 = ip("192.168.43.1")
        val result = wirelessHostAddress(listOf(ip("fe80::1234"), ipv4), 7)
        assertEquals(ipv4, result)
    }

    @Test fun manualApPrefersIpv4WhenIpv4ComesFirst() {
        val ipv4 = ip("192.168.43.1")
        val result = wirelessHostAddress(listOf(ipv4, ip("fe80::1234")), 7)
        assertEquals(ipv4, result)
    }

    @Test fun fallsBackToScopedLinkLocalWithoutUsableIpv4() {
        val result = wirelessHostAddress(listOf(ip("::1"), ip("2001:db8::1"), ip("fe80::1234")), 7) as Inet6Address
        assertTrue(result.isLinkLocalAddress)
        assertEquals(7, result.scopeId)
    }

    @Test fun replacesScopeFromAnotherInterface() {
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        assertEquals(8, (wirelessHostAddress(listOf(wrongScope), 8) as Inet6Address).scopeId)
    }

    @Test fun returnsNullWithoutUsableAddresses() {
        assertNull(wirelessHostAddress(listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251")), 7))
        assertNull(wirelessHostAddress(emptyList(), 7))
        assertNull(wirelessHostAddress(listOf(ip("fe80::1234")), 0))
    }

    private fun ip(value: String) = InetAddress.getByName(value)
}
