package com.styly.deviceid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

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
}
