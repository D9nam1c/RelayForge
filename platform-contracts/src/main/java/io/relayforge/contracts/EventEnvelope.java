package io.relayforge.contracts;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Versioned immutable event carried between ingest and dispatch. Payload bytes are preserved exactly. */
public record EventEnvelope(
        UUID messageId,
        String type,
        int schemaVersion,
        UUID tenantId,
        String sourceId,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String traceparent,
        String payloadContentType,
        byte[] payload) {

    public EventEnvelope {
        Objects.requireNonNull(messageId, "messageId");
        requireText(type, "type");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(tenantId, "tenantId");
        requireText(sourceId, "sourceId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        requireText(payloadContentType, "payloadContentType");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
