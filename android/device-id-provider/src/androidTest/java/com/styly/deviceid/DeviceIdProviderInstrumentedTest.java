package com.styly.deviceid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class DeviceIdProviderInstrumentedTest {
    @Test
    public void getOrCreate_doesNotMintFromANonAuthoritativeMediaStoreView() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (MediaAccess.canReadAllImages(context)) {
            return;
        }

        DeviceIdResult result = DeviceIdProvider.getOrCreate(context);

        assertEquals(DeviceIdStatus.ACCESS_DENIED, result.getStatus());
        assertFalse(result.wasMintAttempted());
    }

    @Test
    public void getOrCreateAsync_deniedAccessIsTerminalWithoutMinting() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(!MediaAccess.canReadAllImages(context));
        DeviceIdResult result = DeviceIdProvider.getOrCreateAsync(context, 5000, 100)
                .get(10, TimeUnit.SECONDS);
        assertEquals(DeviceIdStatus.ACCESS_DENIED, result.getStatus());
        assertFalse(result.wasMintAttempted());
    }

    @Test
    public void getOrCreateAsync_returnsExistingIdWithoutMinting() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceIdResult existing = DeviceIdProvider.find(context);
        assumeTrue(existing.getStatus() == DeviceIdStatus.SUCCESS);
        DeviceIdResult result = DeviceIdProvider.getOrCreateAsync(context, 5000, 100)
                .get(10, TimeUnit.SECONDS);
        assertEquals(DeviceIdStatus.SUCCESS, result.getStatus());
        assertEquals(existing.getDeviceId(), result.getDeviceId());
        assertFalse(result.wasMintAttempted());
    }
}
