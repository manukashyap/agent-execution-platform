package com.conversive.aep.common;

import io.temporal.failure.ApplicationFailure;

/** Maps the platform error taxonomy onto Temporal failures; the failure type is the error code. */
public final class Failures {

    private Failures() {
    }

    public static ApplicationFailure toApplicationFailure(Throwable error) {
        if (error instanceof ApplicationFailure failure) {
            return failure;
        }
        if (error instanceof NonRetryableError e) {
            return ApplicationFailure.newNonRetryableFailureWithCause(e.getMessage(), e.code(), e.getCause());
        }
        if (error instanceof RetryableError e) {
            return ApplicationFailure.newFailureWithCauseAndDelay(
                    e.getMessage(), e.code(), e.getCause(), e.nextRetryDelay());
        }
        return ApplicationFailure.newFailureWithCause(
                String.valueOf(error.getMessage()), ErrorCodes.INTERNAL, error);
    }
}
