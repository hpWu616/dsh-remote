package com.example.dsh_apk;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ExampleUnitTest {
    @Test
    public void discoveredLocalHostIsAllowed() throws Exception {
        assertEquals("http://dsh-0123456789ab.local:19388",
                RemoteClient.normalizeUrl("http://dsh-0123456789ab.local:19388/"));
    }

    @Test
    public void unrelatedCleartextHostIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                RemoteClient.normalizeUrl("http://unrelated.local:19388"));
    }

    @Test
    public void privateComputerAddressesCanBeLookedUp() {
        org.junit.Assert.assertTrue(DiscoveryClient.isPrivateAddressUrl(
                "http://10.15.223.91:19388"));
        org.junit.Assert.assertTrue(DiscoveryClient.isPrivateAddressUrl(
                "http://172.23.4.5:19388"));
        org.junit.Assert.assertTrue(DiscoveryClient.isPrivateAddressUrl(
                "http://192.168.1.20:19388"));
        org.junit.Assert.assertFalse(DiscoveryClient.isPrivateAddressUrl(
                "http://8.8.8.8:19388"));
        org.junit.Assert.assertFalse(DiscoveryClient.isPrivateAddressUrl(
                "http://10.15.223.91.evil.example:19388"));
        org.junit.Assert.assertTrue(DiscoveryClient.isPrivateSecureUrl(
                "https://10.15.223.91:19390"));
        org.junit.Assert.assertFalse(DiscoveryClient.isPrivateSecureUrl(
                "https://8.8.8.8:19390"));
    }
}
