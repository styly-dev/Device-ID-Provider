package com.styly.deviceid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

public final class AsyncDeviceIdRequestTest {
    private static final DeviceIdResult SUCCESS = DeviceIdResult.success(
            "4b4a900c-0abd-4769-b893-96a81b1d496e", 1, false);

    private abstract static class Backend implements AsyncDeviceIdRequest.Backend {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch observed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Runnable changed;

        @Override
        public void observe(Runnable changed) {
            this.changed = changed;
            observed.countDown();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }
    }

    @Test
    public void start_completesAndUnregistersWhenAlreadyReady() throws Exception {
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                assertNotNull(changed);
                calls.incrementAndGet();
                return SUCCESS;
            }
        };

        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(5000);

        assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
        await(backend.closed);
        assertEquals(1, backend.closes.get());
        backend.changed.run();
        assertEquals(1, backend.calls.get());
    }

    @Test
    public void start_coalescesNotificationDuringLookup()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                    return null;
                }
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 30000).start(5000);

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                backend.changed.run();
            }
            release.countDown();
            assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
            await(backend.closed);
            assertEquals(2, backend.calls.get());
            assertEquals(1, backend.closes.get());
        } finally {
            release.countDown();
            future.cancel(false);
        }
    }

    @Test
    public void start_retriesUnavailableStorageWithoutNotification() throws Exception {
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                return calls.incrementAndGet() < 3 ? null : SUCCESS;
            }
        };

        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(5000);

        assertSame(SUCCESS, future.get(2, TimeUnit.SECONDS));
        await(backend.closed);
        assertEquals(3, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void start_returnsTerminalPermissionFailure() throws Exception {
        DeviceIdResult denied = DeviceIdResult.failure(
                DeviceIdStatus.ACCESS_DENIED, false, "denied");
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                return denied;
            }
        };

        DeviceIdResult result = new AsyncDeviceIdRequest(backend, 10)
                .start(5000)
                .get(2, TimeUnit.SECONDS);

        assertSame(denied, result);
        await(backend.closed);
        assertEquals(1, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void start_timesOutBeforeBlockedLookupAndCleanupReturn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(100);

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimesOut(future);
            assertEquals(0, backend.closes.get());
        } finally {
            release.countDown();
        }

        await(backend.closed);
        backend.changed.run();
        assertEquals(1, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void cancel_returnsBeforeBlockedLookupAndCleanupReturn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                exited.countDown();
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(5000);

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            assertTrue(future.isCancelled());
            assertEquals(0, backend.closes.get());
        } finally {
            release.countDown();
        }

        assertTrue(exited.await(2, TimeUnit.SECONDS));
        await(backend.closed);
        backend.changed.run();
        assertEquals(1, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void start_closesResourcesAfterRegistrationFailure() throws Exception {
        Backend backend = new Backend() {
            @Override
            public void observe(Runnable changed) {
                throw new IllegalStateException("failed");
            }

            @Override
            public DeviceIdResult lookup() {
                fail("Must not look up");
                return null;
            }
        };

        try {
            new AsyncDeviceIdRequest(backend, 10).start(5000).get(2, TimeUnit.SECONDS);
            fail("Expected failure");
        } catch (ExecutionException error) {
            assertEquals("failed", error.getCause().getMessage());
        }
        await(backend.closed);
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void start_timesOutAndClosesLateRegistration()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public void observe(Runnable changed) {
                entered.countDown();
                await(release);
                super.observe(changed);
            }

            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(100);

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimesOut(future);
            assertEquals(0, backend.closes.get());
        } finally {
            release.countDown();
        }

        await(backend.observed);
        await(backend.closed);
        backend.changed.run();
        assertEquals(0, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void cancel_returnsAndClosesLateRegistration()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public void observe(Runnable changed) {
                entered.countDown();
                await(release);
                super.observe(changed);
            }

            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(5000);

        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> cancellation =
                    CompletableFuture.supplyAsync(() -> future.cancel(false));
            assertTrue(cancellation.get(1, TimeUnit.SECONDS));
            assertTrue(future.isCancelled());
            assertEquals(0, backend.closes.get());
        } finally {
            release.countDown();
        }

        await(backend.observed);
        await(backend.closed);
        backend.changed.run();
        assertEquals(0, backend.calls.get());
        assertEquals(1, backend.closes.get());
    }

    @Test
    public void start_completesBeforeBlockedCleanupReturns() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch closeRelease = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                return SUCCESS;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
                closeEntered.countDown();
                await(closeRelease);
                closed.countDown();
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(5000);

        try {
            assertSame(SUCCESS, future.get(1, TimeUnit.SECONDS));
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, backend.closes.get());
        } finally {
            closeRelease.countDown();
        }

        await(backend.closed);
    }

    @Test
    public void start_timesOutBeforeBlockedCleanupReturns() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch closeRelease = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                return null;
            }

            @Override
            public void close() {
                closes.incrementAndGet();
                closeEntered.countDown();
                await(closeRelease);
                closed.countDown();
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 30000).start(100);

        try {
            assertTimesOut(future);
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, backend.closes.get());
        } finally {
            closeRelease.countDown();
        }

        await(backend.closed);
    }

    @Test
    public void start_stopsLookupsBeforeBlockedUserHandlerReturns() throws Exception {
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch lookupRelease = new CountDownLatch(1);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch handlerRelease = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                lookupEntered.countDown();
                await(lookupRelease);
                return SUCCESS;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(5000);

        assertTrue(lookupEntered.await(2, TimeUnit.SECONDS));
        future.whenComplete((value, error) -> {
            handlerEntered.countDown();
            await(handlerRelease);
        });
        lookupRelease.countDown();
        try {
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            await(backend.closed);
            backend.changed.run();
            assertEquals(1, backend.calls.get());
            assertEquals(1, backend.closes.get());
        } finally {
            handlerRelease.countDown();
        }
    }

    @Test
    public void cancel_stopsLookupsBeforeBlockedUserHandlerReturns() throws Exception {
        CountDownLatch lookupCalled = new CountDownLatch(1);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch handlerRelease = new CountDownLatch(1);
        Backend backend = new Backend() {
            @Override
            public DeviceIdResult lookup() {
                calls.incrementAndGet();
                lookupCalled.countDown();
                return null;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 30000).start(5000);

        assertTrue(lookupCalled.await(2, TimeUnit.SECONDS));
        future.whenComplete((value, error) -> {
            handlerEntered.countDown();
            await(handlerRelease);
        });
        CompletableFuture<Boolean> cancellation =
                CompletableFuture.supplyAsync(() -> future.cancel(false));
        try {
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            await(backend.closed);
            backend.changed.run();
            assertEquals(1, backend.calls.get());
            assertEquals(1, backend.closes.get());
        } finally {
            handlerRelease.countDown();
        }
        assertTrue(cancellation.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void start_preservesLatestRetryFailureAsTimeoutCause() throws Exception {
        IllegalArgumentException latest = new IllegalArgumentException("Required column is missing");
        Backend backend = new Backend() {
            private volatile Throwable lastFailure;

            @Override
            public DeviceIdResult lookup() {
                lastFailure = calls.incrementAndGet() == 1
                        ? new IllegalArgumentException("Initial readiness failure") : latest;
                return null;
            }

            @Override
            public Throwable lastRetryCause() {
                return lastFailure;
            }
        };
        CompletableFuture<DeviceIdResult> future =
                new AsyncDeviceIdRequest(backend, 10).start(500);

        try {
            future.get(2, TimeUnit.SECONDS);
            fail("Expected timeout");
        } catch (ExecutionException error) {
            assertTrue(error.getCause() instanceof TimeoutException);
            assertSame(latest, error.getCause().getCause());
            assertTrue(backend.calls.get() >= 2);
        } finally {
            future.cancel(false);
        }
        await(backend.closed);
    }

    private static void assertTimesOut(CompletableFuture<DeviceIdResult> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("Expected timeout");
        } catch (ExecutionException error) {
            assertTrue(error.getCause() instanceof TimeoutException);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch timeout");
            }
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
    }
}
