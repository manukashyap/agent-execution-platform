package com.conversive.aep.engine.activity;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import io.temporal.failure.ApplicationFailure;

/** The platform error code carried by an exception ({@code INTERNAL} when it carries none). */
final class ErrorCodeOf {

    private ErrorCodeOf() {
    }

    static String code(Throwable e) {
        if (e instanceof NonRetryableError n) {
            return n.code();
        }
        if (e instanceof RetryableError r) {
            return r.code();
        }
        if (e instanceof ApplicationFailure a && a.getType() != null) {
            return a.getType();
        }
        return ErrorCodes.INTERNAL;
    }
}
