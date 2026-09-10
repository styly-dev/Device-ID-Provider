package com.styly.deviceid;

import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AsyncDeviceIdRequestTest {
    private static final DeviceIdResult SUCCESS = DeviceIdResult.success(
            "4b4a900c-0abd-4769-b893-96a81b1d496e", 1, false);

    private abstract static class Backend implements AsyncDeviceIdRequest.Backend {
        volatile Runnable changed;
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        @Override public void observe(Runnable changed) { this.changed = changed; }
        @Override public void close() { closes.incrementAndGet(); }
    }

    @Test public void alreadyReadyCompletesAndUnregisters() throws Exception {
        Backend backend = new Backend() {
            @Override public DeviceIdResult lookup() {
                assertNotNull(changed);
                calls.incrementAndGet();
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 10).start(5000);
        assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
        assertEquals(1, backend.closes.get());
        backend.changed.run();
        assertEquals(1, backend.calls.get());
    }

    @Test public void notificationDuringLookupIsCoalescedAndRecheckedWithoutWaitingForTimer()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override public DeviceIdResult lookup() {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                    return null;
                }
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 30000).start(5000);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) backend.changed.run();
            release.countDown();
            assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
            assertEquals(2, backend.calls.get());
            assertEquals(1, backend.closes.get());
        } finally {
            release.countDown();
            future.cancel(false);
        }
    }

    @Test public void unavailableStorageRetriesWithoutNotification() throws Exception {
        Backend backend = new Backend() {
            @Override public DeviceIdResult lookup() {
                return calls.incrementAndGet() < 3 ? null : SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 10).start(5000);
        assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
        assertEquals(3, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test public void permissionFailureIsTerminal() throws Exception {
        DeviceIdResult denied = DeviceIdResult.failure(DeviceIdStatus.ACCESS_DENIED, false, "denied");
        Backend backend = new Backend() {
            @Override public DeviceIdResult lookup() { calls.incrementAndGet(); return denied; }
        };
        assertSame(denied, new AsyncDeviceIdRequest(backend, 10).start(5000).get(2, TimeUnit.SECONDS));
        assertEquals(1, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test public void timeoutCompletesEvenWhileLookupIsBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override public DeviceIdResult lookup() {
                entered.countDown(); await(release); return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 10).start(500);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try { future.get(2, TimeUnit.SECONDS); fail("Expected timeout"); }
            catch (ExecutionException error) { assertTrue(error.getCause() instanceof TimeoutException); }
            assertEquals(1, backend.closes.get());
        } finally { release.countDown(); future.cancel(false); }
    }

    @Test public void cancellationClosesObserverAndDiscardsInFlightResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override public DeviceIdResult lookup() {
                calls.incrementAndGet(); entered.countDown(); await(release);
                exited.countDown(); return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 10).start(5000);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            AtomicInteger closesSeenByUserCallback = new AtomicInteger(-1);
            future.whenComplete((value, error) -> closesSeenByUserCallback.set(backend.closes.get()));
            assertTrue(future.cancel(false));
            assertEquals(1, closesSeenByUserCallback.get());
            assertEquals(1, backend.closes.get());
            backend.changed.run();
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertTrue(future.isCancelled());
            assertEquals(1, backend.calls.get());
        } finally { release.countDown(); future.cancel(false); }
    }

    @Test public void registrationFailureClosesResources() throws Exception {
        Backend backend = new Backend() {
            @Override public void observe(Runnable changed) { throw new IllegalStateException("failed"); }
            @Override public DeviceIdResult lookup() { fail("Must not look up"); return null; }
        };
        try {
            new AsyncDeviceIdRequest(backend, 10).start(5000).get(2, TimeUnit.SECONDS);
            fail("Expected failure");
        } catch (ExecutionException error) { assertEquals("failed", error.getCause().getMessage()); }
        assertEquals(1, backend.closes.get());
    }

    @Test public void onlyKnownPreMintVolumeFailuresAreRetried() {
        String message = "IllegalArgumentException: Volume external_primary not found";
        assertTrue(AndroidAsyncDeviceIdBackend.isRetryable(
                DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, false, message)));
        assertFalse(AndroidAsyncDeviceIdBackend.isRetryable(
                DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, true, message)));
        assertFalse(AndroidAsyncDeviceIdBackend.isRetryable(
                DeviceIdResult.failure(DeviceIdStatus.IO_ERROR, false, "disk full")));
        assertFalse(AndroidAsyncDeviceIdBackend.isRetryable(
                DeviceIdResult.failure(DeviceIdStatus.ACCESS_DENIED, false, message)));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }
}
