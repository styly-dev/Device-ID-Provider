package com.styly.deviceid;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns one request; Android storage access is isolated behind Backend for host tests. */
final class AsyncDeviceIdRequest {
    interface Backend {
        void observe(Runnable onChanged);
        // null means storage is temporarily unavailable; all other results are terminal.
        DeviceIdResult lookup();
        void close();
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
            boolean cancelled;
            try {
                close();
            } finally {
                // Release resources before user completion handlers can block this thread.
                cancelled = super.cancel(false);
            }
            return cancelled;
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
        events.schedule(() -> finish(null,
                new TimeoutException("Device ID lookup did not complete within the requested timeout")),
                timeoutMillis, TimeUnit.MILLISECONDS);
        dispatch(() -> {
            try {
                synchronized (lock) {
                    if (closed) return;
                    // Register first, then inspect current state in lookup().
                    backend.observe(() -> dispatch(this::attempt));
                }
                attempt();
            } catch (RuntimeException error) {
                finish(null, error);
            }
        });
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
            try {
                events.execute(task);
            } catch (RejectedExecutionException error) {
                if (!closed) throw error;
            }
        }
    }

    private void finish(DeviceIdResult value, Throwable error) {
        try {
            close();
        } catch (RuntimeException cleanupError) {
            if (error == null) error = cleanupError;
            else error.addSuppressed(cleanupError);
        }
        if (error == null) result.complete(value);
        else result.completeExceptionally(error);
    }

    private void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            try {
                backend.close();
            } finally {
                // Do not interrupt an in-flight MediaStore operation or roll back an ID it created.
                worker.shutdown();
                events.shutdown();
            }
        }
    }
}
