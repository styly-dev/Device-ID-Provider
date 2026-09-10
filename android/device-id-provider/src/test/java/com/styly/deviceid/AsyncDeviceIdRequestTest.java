package com.styly.deviceid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AsyncDeviceIdRequestTest {
    private static final DeviceIdResult SUCCESS = DeviceIdResult.success(
            "4b4a900c-0abd-4769-b893-96a81b1d496e", 1, false);

    @Test
    public void start_returnsReadyResultOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(() -> {
            calls.incrementAndGet();
            return SUCCESS;
        }, 10).start(5000);

        assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    public void start_retriesOnlyReadinessFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(
                () -> calls.incrementAndGet() < 3 ? null : SUCCESS, 10).start(5000);

        assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
        assertEquals(3, calls.get());
    }

    @Test
    public void start_preservesTerminalProviderFailureWithoutRetry() throws Exception {
        for (DeviceIdStatus status : new DeviceIdStatus[] {
                DeviceIdStatus.ACCESS_DENIED, DeviceIdStatus.IO_ERROR }) {
            DeviceIdResult failure = DeviceIdResult.failure(status, false, "provider failed");
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(() -> {
                calls.incrementAndGet();
                return failure;
            }, 10).start(5000);

            assertSame(failure, future.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void start_propagatesUnexpectedLookupException() throws Exception {
        IllegalStateException failure = new IllegalStateException("lookup failed");
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(() -> {
            throw failure;
        }, 10).start(5000);

        assertSame(failure, failureOf(future));
    }

    @Test
    public void start_timesOutWhileLookupIsBlocked() throws Exception {
        verifyBlockedLookup(false);
    }

    @Test
    public void cancel_doesNotInterruptLookupOrAcceptItsLateResult() throws Exception {
        verifyBlockedLookup(true);
    }

    @Test
    public void cancel_wakesLongRetryWaitAndStopsFurtherLookups() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(() -> {
            worker.set(Thread.currentThread());
            calls.incrementAndGet();
            called.countDown();
            return null;
        }, 30000).start(5000);

        try {
            assertTrue(called.await(2, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            worker.get().join(1000);
            assertFalse(worker.get().isAlive());
            assertEquals(1, calls.get());
        } finally {
            future.cancel(false);
        }
    }

    @Test
    public void start_preservesLatestRetryFailureAsTimeoutCause() throws Exception {
        IllegalArgumentException latest = new IllegalArgumentException("Required column is missing");
        AtomicInteger calls = new AtomicInteger();
        AsyncDeviceIdRequest.Backend backend = new AsyncDeviceIdRequest.Backend() {
            private volatile Throwable cause;

            @Override
            public DeviceIdResult lookup() {
                cause = calls.incrementAndGet() == 1
                        ? new IllegalStateException("Primary storage is unmounted") : latest;
                return null;
            }

            @Override
            public Throwable lastRetryCause() {
                return cause;
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 10).start(500);

        Throwable failure = failureOf(future);
        assertTrue(failure instanceof TimeoutException);
        assertSame(latest, failure.getCause());
        assertTrue(calls.get() >= 2);
    }

    @Test
    public void start_timesOutEvenWhenRetryDiagnosticsThrow() throws Exception {
        AsyncDeviceIdRequest.Backend backend = new AsyncDeviceIdRequest.Backend() {
            @Override
            public DeviceIdResult lookup() {
                return null;
            }

            @Override
            public Throwable lastRetryCause() {
                throw new IllegalStateException("Diagnostics failed");
            }
        };
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(backend, 10).start(100);

        try {
            assertTrue(failureOf(future) instanceof TimeoutException);
        } finally {
            future.cancel(false);
        }
    }

    private static void verifyBlockedLookup(boolean cancel) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger interruptions = new AtomicInteger();
        CompletableFuture<DeviceIdResult> future = new AsyncDeviceIdRequest(() -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                interruptions.incrementAndGet();
                throw new AssertionError("Storage lookup must not be interrupted", error);
            }
            return SUCCESS;
        }, 10).start(cancel ? 5000 : 200);

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            if (cancel) {
                assertTrue(future.cancel(true));
                assertTrue(future.isCancelled());
            } else {
                assertTrue(failureOf(future) instanceof TimeoutException);
            }
        } finally {
            release.countDown();
            future.cancel(false);
        }
        worker.get().join(1000);
        assertFalse(worker.get().isAlive());
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, interruptions.get());
    }

    private static Throwable failureOf(CompletableFuture<DeviceIdResult> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("Expected exceptional completion");
            return null;
        } catch (ExecutionException error) {
            return error.getCause();
        }
    }
}
