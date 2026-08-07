package io.relayforge.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Sanitized result: no response body, endpoint URL, authorization value, or payload. */
public record AttemptResult(
        UUID resultId,
        UUID commandId,
        UUID deliveryId,
        UUID tenantId,
        int executionGeneration,
        int attemptNumber,
        AttemptOutcome outcome,
        Integer httpStatus,
        Duration latency,
        Instant completedAt,
        Instant retryAfter,
        long responseBytes,
        String detail) {
    public AttemptResult {
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(tenantId, "tenantId");
        if (executionGeneration < 0) throw new IllegalArgumentException("executionGeneration must not be negative");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(completedAt, "completedAt");
        if (responseBytes < 0) throw new IllegalArgumentException("responseBytes must not be negative");
        detail = sanitize(detail);
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160);
    }
}
