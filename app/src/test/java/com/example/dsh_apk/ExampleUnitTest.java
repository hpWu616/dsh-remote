package com.example.dsh_apk;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExampleUnitTest {
    @Test
    public void onlyPrivateHttpsComputerAddressesAreAllowed() {
        assertTrue(DiscoveryClient.isPrivateSecureUrl("https://10.15.223.91:19390"));
        assertTrue(DiscoveryClient.isPrivateSecureUrl("https://172.23.4.5:19390"));
        assertTrue(DiscoveryClient.isPrivateSecureUrl("https://192.168.1.20:19390"));
        assertTrue(DiscoveryClient.isPrivateSecureUrl("https://127.0.0.1:19390"));
        assertFalse(DiscoveryClient.isPrivateSecureUrl("http://10.15.223.91:19388"));
        assertFalse(DiscoveryClient.isPrivateSecureUrl("https://8.8.8.8:19390"));
        assertFalse(DiscoveryClient.isPrivateSecureUrl(
                "https://10.15.223.91.evil.example:19390"));
    }
}
