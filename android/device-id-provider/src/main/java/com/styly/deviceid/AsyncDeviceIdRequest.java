package com.styly.deviceid;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Polls readiness on one worker; an independent timer bounds even a blocked lookup. */
final class AsyncDeviceIdRequest {
    interface Backend {
        // Called serially on the worker. null means retry; other results are terminal.
        DeviceIdResult lookup();
        // Read concurrently by the deadline thread; must be thread-safe and nonblocking.
        default Throwable lastRetryCause() { return null; }
    }

    private final Backend backend;
    private final long retryDelayMillis;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
            1, task -> new Thread(task, "device-id-deadline"));
    private final CompletableFuture<DeviceIdResult> result = new CompletableFuture<DeviceIdResult>() {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            timer.shutdown();
            return super.cancel(false);
        }
    };

    AsyncDeviceIdRequest(Backend backend, long retryDelayMillis) {
        this.backend = backend;
        this.retryDelayMillis = retryDelayMillis;
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    CompletableFuture<DeviceIdResult> start(long timeoutMillis) {
        timer.schedule(() -> {
            TimeoutException timeout = new TimeoutException(
                    "Device ID lookup did not complete within the requested timeout");
            timeout.initCause(backend.lastRetryCause());
            finish(null, timeout);
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        new Thread(this::poll, "device-id-lookup").start();
        return result;
    }

    private void poll() {
        try {
            while (!result.isDone()) {
                DeviceIdResult value = backend.lookup();
                if (value != null) {
                    finish(value, null);
                    return;
                }
                try {
                    // Completion/cancellation wakes this wait without interrupting storage I/O.
                    result.get(retryDelayMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException retry) {
                    // The retry delay elapsed; check readiness again.
                }
            }
        } catch (CancellationException | ExecutionException completed) {
            // The request ended while waiting between attempts.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            finish(null, error);
        } catch (RuntimeException error) {
            finish(null, error);
        } finally {
            timer.shutdown();
        }
    }

    private void finish(DeviceIdResult value, Throwable error) {
        // Stop the deadline before user completion handlers can block this thread.
        timer.shutdown();
        if (error == null) result.complete(value);
        else result.completeExceptionally(error);
    }
}
