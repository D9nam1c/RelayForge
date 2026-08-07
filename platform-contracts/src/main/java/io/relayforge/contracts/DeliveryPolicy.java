package io.relayforge.contracts;

import java.time.Duration;

public record DeliveryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        Duration maxRetryAfter,
        int endpointConcurrency,
        boolean preserveOrder) {

    public DeliveryPolicy {
        if (maxAttempts < 1 || maxAttempts > 25) throw new IllegalArgumentException("maxAttempts must be 1..25");
        requirePositive(initialDelay, "initialDelay");
        requirePositive(maxDelay, "maxDelay");
        requirePositive(maxRetryAfter, "maxRetryAfter");
        if (initialDelay.compareTo(maxDelay) > 0) throw new IllegalArgumentException("initialDelay exceeds maxDelay");
        if (maxDelay.compareTo(Duration.ofDays(7)) > 0) throw new IllegalArgumentException("maxDelay exceeds 7 days");
        if (maxRetryAfter.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("maxRetryAfter exceeds 1 day");
        }
        if (endpointConcurrency < 1 || endpointConcurrency > 100) {
            throw new IllegalArgumentException("endpointConcurrency must be 1..100");
        }
        if (preserveOrder && endpointConcurrency != 1) {
            throw new IllegalArgumentException("ordered endpoints require concurrency 1");
        }
    }

    public static DeliveryPolicy defaults() {
        return new DeliveryPolicy(6, Duration.ofSeconds(2), Duration.ofMinutes(15), Duration.ofMinutes(15), 4, false);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
