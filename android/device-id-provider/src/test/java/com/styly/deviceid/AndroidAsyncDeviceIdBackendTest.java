package com.styly.deviceid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AndroidAsyncDeviceIdBackendTest {
    @Test
    public void isRetryable_acceptsPreMintIllegalArgumentFailure() {
        DeviceIdResult value = DeviceIdResult.failure(
                DeviceIdStatus.IO_ERROR, false, "wording is not part of classification");

        assertTrue(AndroidAsyncDeviceIdBackend.isRetryable(
                value, new IllegalArgumentException("provider unavailable")));
    }

    @Test
    public void isRetryable_rejectsFailureAfterMintAttempt() {
        DeviceIdResult value = DeviceIdResult.failure(
                DeviceIdStatus.IO_ERROR, true, "provider unavailable");

        assertFalse(AndroidAsyncDeviceIdBackend.isRetryable(
                value, new IllegalArgumentException("provider unavailable")));
    }

    @Test
    public void isRetryable_rejectsOtherFailureType() {
        DeviceIdResult value = DeviceIdResult.failure(
                DeviceIdStatus.IO_ERROR, false, "provider unavailable");

        assertFalse(AndroidAsyncDeviceIdBackend.isRetryable(
                value, new IllegalStateException("provider unavailable")));
    }

    @Test
    public void isRetryable_rejectsOtherResultStatus() {
        DeviceIdResult value = DeviceIdResult.failure(
                DeviceIdStatus.ACCESS_DENIED, false, "provider unavailable");

        assertFalse(AndroidAsyncDeviceIdBackend.isRetryable(
                value, new IllegalArgumentException("provider unavailable")));
    }
}
