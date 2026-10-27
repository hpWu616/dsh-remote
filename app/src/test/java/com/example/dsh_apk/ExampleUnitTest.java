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
}
