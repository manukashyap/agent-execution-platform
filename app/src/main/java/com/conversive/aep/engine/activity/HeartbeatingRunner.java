package com.conversive.aep.engine.activity;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Runs node work on a virtual thread while the activity thread heartbeats, so a dead worker is detected
 * by the heartbeat timeout and a cancel request reaches the activity. On cancellation non-side-effecting
 * work is interrupted; side-effecting work is left to finish (WAIT_CANCELLATION_COMPLETED) and its result
 * is returned, so the saga sees what actually happened.
 */
@Component
public class HeartbeatingRunner implements AutoCloseable {

    /** Heartbeat cadence; the interpreter's heartbeat timeout must be a few multiples of it. */
    public static final Duration INTERVAL = Duration.ofSeconds(1);

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    public <T> T run(ActivityExecutionContext ctx, boolean letFinishOnCancel, Supplier<T> work) {
        Future<T> future = threads.submit(work::get);
        while (true) {
            try {
                return future.get(INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                heartbeat(ctx, future, letFinishOnCancel);
            } catch (ExecutionException e) {
                throw unwrap(e);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("activity thread interrupted", e);
            }
        }
    }

    private static <T> void heartbeat(ActivityExecutionContext ctx, Future<T> future, boolean letFinishOnCancel) {
        try {
            ctx.heartbeat(null);
        } catch (ActivityCompletionException cancelled) {
            if (!letFinishOnCancel) {
                future.cancel(true);
                throw cancelled;
            }
        }
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }

    @Override
    public void close() {
        threads.shutdownNow();
    }

    static boolean isCancellation(Throwable e) {
        return e instanceof ActivityCompletionException || e instanceof CancellationException;
    }
}
