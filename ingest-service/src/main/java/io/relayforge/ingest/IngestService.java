package io.relayforge.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import io.relayforge.contracts.EventEnvelope;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class IngestService {
    private static final Set<String> CONTENT_TYPES = Set.of("application/json", "application/cloudevents+json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final int maxPayloadBytes;
    private final MeterRegistry metrics;
    private final Clock clock;

    IngestService(JdbcTemplate jdbc, @Value("${relayforge.payload.max-bytes:262144}") int maxPayloadBytes,
                  MeterRegistry metrics) {
        this(jdbc, maxPayloadBytes, metrics, Clock.systemUTC());
    }

    IngestService(JdbcTemplate jdbc, int maxPayloadBytes, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.maxPayloadBytes = maxPayloadBytes;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    AcceptedEvent accept(UUID tenantId, String idempotencyKey, String eventType, int schemaVersion, String sourceId,
                         String contentType, byte[] payload, UUID correlationId, UUID causationId, String traceparent) {
        validate(idempotencyKey, eventType, schemaVersion, sourceId, contentType, payload);
        String fingerprint = fingerprint(eventType, schemaVersion, sourceId, contentType, payload);
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            jdbc.update("""
                    insert into events(id, tenant_id, idempotency_key, request_hash, event_type, schema_version,
                      source_id, occurred_at, correlation_id, causation_id, traceparent, content_type, payload, accepted_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, eventId, tenantId, idempotencyKey, fingerprint, eventType, schemaVersion, sourceId, now,
                    correlationId, causationId, traceparent, contentType, payload, now);
            jdbc.update("insert into outbox(id, event_id, created_at) values (?, ?, ?)", UUID.randomUUID(), eventId, now);
            metrics.counter("relayforge.events.accepted").increment();
            return new AcceptedEvent(eventId, now, false);
        } catch (DuplicateKeyException duplicate) {
            ExistingEvent existing = jdbc.queryForObject("""
                    select id, request_hash, accepted_at from events where tenant_id = ? and idempotency_key = ?
                    """, (rs, row) -> new ExistingEvent(rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getTimestamp(3).toInstant()), tenantId, idempotencyKey);
            if (existing == null || !MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                metrics.counter("relayforge.events.rejected", "reason", "idempotency_conflict").increment();
                throw new IdempotencyConflictException();
            }
            metrics.counter("relayforge.events.idempotency.hits").increment();
            return new AcceptedEvent(existing.id(), existing.acceptedAt(), true);
        }
    }

    EventView find(UUID tenantId, UUID eventId) {
        var events = jdbc.query("select * from events where tenant_id = ? and id = ?", (rs, row) -> mapEvent(rs),
                tenantId, eventId);
        if (events.isEmpty()) throw new IngestNotFoundException();
        EventEnvelope event = events.getFirst();
        return new EventView(event.messageId(), event.type(), event.schemaVersion(), event.sourceId(),
                event.occurredAt(), event.correlationId(), event.payloadContentType(), event.payload().length);
    }

    static String fingerprint(String eventType, int schemaVersion, String sourceId, String contentType, byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, eventType.getBytes(StandardCharsets.UTF_8));
            update(digest, ByteBuffer.allocate(Integer.BYTES).putInt(schemaVersion).array());
            update(digest, sourceId.getBytes(StandardCharsets.UTF_8));
            update(digest, contentType.getBytes(StandardCharsets.US_ASCII));
            update(digest, payload);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private void validate(String key, String type, int schema, String source, String contentType, byte[] payload) {
        if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Idempotency-Key is required and must not exceed 200 characters");
        if (type == null || !type.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) throw new IllegalArgumentException("invalid event type");
        if (schema < 1) throw new IllegalArgumentException("schema version must be positive");
        if (source == null || source.isBlank() || source.length() > 200) throw new IllegalArgumentException("source ID is required");
        if (!CONTENT_TYPES.contains(contentType)) throw new IllegalArgumentException("unsupported content type");
        if (payload == null || payload.length == 0) throw new IllegalArgumentException("payload is required");
        if (payload.length > maxPayloadBytes) {
            metrics.counter("relayforge.events.rejected", "reason", "payload_too_large").increment();
            throw new PayloadTooLargeException(maxPayloadBytes);
        }
        try { JSON.readTree(payload); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("payload must be valid JSON", invalid); }
    }

    static EventEnvelope mapEvent(ResultSet rs) throws SQLException {
        return new EventEnvelope(rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getInt("schema_version"), rs.getObject("tenant_id", UUID.class), rs.getString("source_id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class), rs.getString("traceparent"), rs.getString("content_type"),
                rs.getBytes("payload"));
    }

    record AcceptedEvent(UUID eventId, Instant acceptedAt, boolean idempotencyHit) {}
    record EventView(UUID eventId, String type, int schemaVersion, String sourceId, Instant occurredAt,
                     UUID correlationId, String contentType, int payloadBytes) {}
    private record ExistingEvent(UUID id, String requestHash, Instant acceptedAt) {}
}
