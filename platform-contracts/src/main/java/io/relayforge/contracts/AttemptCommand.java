package io.relayforge.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AttemptCommand(
        UUID commandId,
        UUID deliveryId,
        int executionGeneration,
        int attemptNumber,
        Instant leaseExpiresAt,
        EventEnvelope event,
        EndpointSnapshot endpoint) {
    public AttemptCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        if (executionGeneration < 0) throw new IllegalArgumentException("executionGeneration must not be negative");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(endpoint, "endpoint");
        if (!event.tenantId().equals(endpoint.tenantId())) throw new IllegalArgumentException("tenant mismatch");
    }
}
