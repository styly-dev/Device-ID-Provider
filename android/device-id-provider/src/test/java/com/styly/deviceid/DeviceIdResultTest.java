package com.styly.deviceid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceIdResultTest {
    @Test
    public void success_exposesDuplicateDiagnostic() {
        DeviceIdResult result = DeviceIdResult.success(
                "00000000-0000-0000-0000-000000000001",
                2,
                true);

        assertEquals(DeviceIdStatus.SUCCESS, result.getStatus());
        assertEquals("00000000-0000-0000-0000-000000000001", result.getDeviceId());
        assertEquals(2, result.getCandidateCount());
        assertTrue(result.wasMintAttempted());
        assertFalse(result.getDiagnosticMessage().isEmpty());
    }

    @Test
    public void failure_hasNoDeviceId() {
        DeviceIdResult result = DeviceIdResult.failure(
                DeviceIdStatus.ACCESS_DENIED,
                false,
                "denied");

        assertEquals(DeviceIdStatus.ACCESS_DENIED, result.getStatus());
        assertNull(result.getDeviceId());
        assertEquals("denied", result.getDiagnosticMessage());
    }
}
