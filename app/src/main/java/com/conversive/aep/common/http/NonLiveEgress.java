package com.conversive.aep.common.http;

import java.util.function.Supplier;

/**
 * Thread-scoped egress permit for non-{@code LIVE} executions. The dry-run executor registry runs the executors
 * its policy (06 §4.10) lets go live inside {@link #permit}; {@link OutboundClient} refuses every other
 * non-{@code LIVE} request, allow-listed hosts included. Executors call their clients synchronously on the
 * activity's runner thread, so the permit covers exactly the calls of that one executor.
 */
public final class NonLiveEgress {

    private static final ThreadLocal<Boolean> PERMIT = new ThreadLocal<>();

    private NonLiveEgress() {
    }

    public static <T> T permit(Supplier<T> call) {
        Boolean previous = PERMIT.get();
        PERMIT.set(Boolean.TRUE);
        try {
            return call.get();
        } finally {
            if (previous == null) {
                PERMIT.remove();
            } else {
                PERMIT.set(previous);
            }
        }
    }

    public static boolean permitted() {
        return Boolean.TRUE.equals(PERMIT.get());
    }
}
