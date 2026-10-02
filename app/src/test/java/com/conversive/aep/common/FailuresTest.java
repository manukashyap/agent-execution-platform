package com.conversive.aep.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.failure.ApplicationFailure;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FailuresTest {

    @Test
    void nonRetryableMapsToNonRetryableFailureTypedByCode() {
        ApplicationFailure failure = Failures.toApplicationFailure(
                new NonRetryableError(ErrorCodes.FANOUT_LIMIT, "too many items"));

        assertThat(failure.isNonRetryable()).isTrue();
        assertThat(failure.getType()).isEqualTo(ErrorCodes.FANOUT_LIMIT);
        assertThat(failure.getOriginalMessage()).isEqualTo("too many items");
    }

    @Test
    void retryableCarriesNextRetryDelay() {
        ApplicationFailure failure = Failures.toApplicationFailure(
                new RetryableError(ErrorCodes.EFFECT_IN_PROGRESS, "lease held", Duration.ofSeconds(7)));

        assertThat(failure.isNonRetryable()).isFalse();
        assertThat(failure.getType()).isEqualTo(ErrorCodes.EFFECT_IN_PROGRESS);
        assertThat(failure.getNextRetryDelay()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void retryableWithoutDelayLeavesBackoffToRetryPolicy() {
        ApplicationFailure failure = Failures.toApplicationFailure(
                new RetryableError(ErrorCodes.UPSTREAM_TIMEOUT, "slow"));

        assertThat(failure.isNonRetryable()).isFalse();
        assertThat(failure.getNextRetryDelay()).isNull();
    }

    @Test
    void unknownExceptionsAreRetryableInternal() {
        ApplicationFailure failure = Failures.toApplicationFailure(new IllegalStateException("boom"));

        assertThat(failure.isNonRetryable()).isFalse();
        assertThat(failure.getType()).isEqualTo(ErrorCodes.INTERNAL);
    }

    @Test
    void existingApplicationFailurePassesThrough() {
        ApplicationFailure original = ApplicationFailure.newNonRetryableFailure("x", "T");

        assertThat(Failures.toApplicationFailure(original)).isSameAs(original);
    }
}
