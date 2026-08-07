package io.relayforge.dispatch;

import io.relayforge.contracts.DeliveryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

final class RetryPlanner {
    private final RandomGenerator random;

    RetryPlanner(RandomGenerator random) { this.random = random; }

    Instant nextAttempt(DeliveryPolicy policy, int completedAttempt, Instant completedAt, Instant retryAfter) {
        long initial = policy.initialDelay().toMillis();
        int exponent = Math.min(Math.max(0, completedAttempt - 1), 30);
        long exponential;
        try { exponential = Math.multiplyExact(initial, 1L << exponent); }
        catch (ArithmeticException overflow) { exponential = Long.MAX_VALUE; }
        long capMillis = Math.min(exponential, policy.maxDelay().toMillis());
        long jitterMillis = capMillis <= 1 ? capMillis : random.nextLong(capMillis + 1);
        Instant planned = completedAt.plusMillis(jitterMillis);
        if (retryAfter != null && !retryAfter.isBefore(completedAt)) {
            Instant bounded = completedAt.plus(policy.maxRetryAfter());
            Instant safeRetryAfter = retryAfter.isAfter(bounded) ? bounded : retryAfter;
            if (safeRetryAfter.isAfter(planned)) planned = safeRetryAfter;
        }
        return planned;
    }

    static boolean retryable(io.relayforge.contracts.AttemptOutcome outcome) {
        return switch (outcome) {
            case RATE_LIMITED, RETRYABLE_HTTP, TIMEOUT, CONNECTION_FAILURE, RESPONSE_TOO_LARGE, ENDPOINT_SATURATED,
                    INTERNAL_FAILURE, LEASE_EXPIRED -> true;
            case SUCCEEDED, PERMANENT_HTTP, BLOCKED_TARGET -> false;
        };
    }
}
