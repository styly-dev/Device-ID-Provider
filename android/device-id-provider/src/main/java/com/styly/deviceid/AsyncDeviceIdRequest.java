package com.styly.deviceid;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns one request; Android storage access is isolated behind Backend for host tests. */
final class AsyncDeviceIdRequest {
    /**
     * observe, lookup and close run serially on one worker, in that order; close is called once
     * after registration and any in-flight lookup return. onChanged may run on any thread.
     * lastRetryCause is read concurrently by the deadline thread and must never block.
     */
    interface Backend {
        void observe(Runnable onChanged);
        // null means storage is temporarily unavailable; all other results are terminal.
        DeviceIdResult lookup();
        void close();
        default Throwable lastRetryCause() { return null; }
    }

    private final Object lock = new Object();
    private final Backend backend;
    private final long retryDelayMillis;
    private final ScheduledThreadPoolExecutor events = new ScheduledThreadPoolExecutor(
            1, task -> new Thread(task, "device-id-events"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "device-id-lookup"));
    private final CompletableFuture<DeviceIdResult> result = new CompletableFuture<DeviceIdResult>() {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            close();
            return super.cancel(false);
        }
    };
    private ScheduledFuture<?> retry;
    private boolean busy;
    private boolean recheckRequested;
    private boolean closed;

    AsyncDeviceIdRequest(Backend backend, long retryDelayMillis) {
        this.backend = backend;
        this.retryDelayMillis = retryDelayMillis;
        events.setRemoveOnCancelPolicy(true);
        events.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    CompletableFuture<DeviceIdResult> start(long timeoutMillis) {
        result.whenComplete((value, error) -> close());
        synchronized (lock) {
            events.schedule(() -> {
                TimeoutException timeout = new TimeoutException(
                        "Device ID lookup did not complete within the requested timeout");
                timeout.initCause(backend.lastRetryCause());
                finish(null, timeout);
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            worker.execute(() -> {
                try {
                    // Registration may call Android system services and must not block the deadline.
                    backend.observe(() -> dispatch(this::attempt));
                    dispatch(this::attempt);
                } catch (RuntimeException error) {
                    dispatch(() -> finish(null, error));
                }
            });
        }
        return result;
    }

    private void attempt() {
        synchronized (lock) {
            if (closed) return;
            if (busy) {
                recheckRequested = true;
                return;
            }
            recheckRequested = false;
            if (retry != null) retry.cancel(false);
            busy = true;
            worker.execute(() -> {
                synchronized (lock) {
                    if (closed) return;
                }
                try {
                    DeviceIdResult value = backend.lookup();
                    dispatch(() -> {
                        synchronized (lock) {
                            if (closed) return;
                            busy = false;
                            if (value == null) {
                                retry = events.schedule(this::attempt,
                                        recheckRequested ? 0 : retryDelayMillis,
                                        TimeUnit.MILLISECONDS);
                                return;
                            }
                        }
                        finish(value, null);
                    });
                } catch (RuntimeException error) {
                    dispatch(() -> finish(null, error));
                }
            });
        }
    }

    private void dispatch(Runnable task) {
        synchronized (lock) {
            if (closed) return;
            events.execute(task);
        }
    }

    private void finish(DeviceIdResult value, Throwable error) {
        close();
        if (error == null) result.complete(value);
        else result.completeExceptionally(error);
    }

    private void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (retry != null) retry.cancel(false);
            // This task runs after registration and any in-flight lookup. Cleanup can therefore
            // unregister a late observer without delaying timeout or cancellation completion.
            worker.execute(this::closeBackend);
            // Do not interrupt an in-flight MediaStore operation or roll back an ID it created.
            worker.shutdown();
            events.shutdown();
        }
    }

    private void closeBackend() {
        try {
            backend.close();
        } catch (RuntimeException ignored) {
            // The future is already terminal; cleanup failure cannot change its outcome.
        }
    }
}
