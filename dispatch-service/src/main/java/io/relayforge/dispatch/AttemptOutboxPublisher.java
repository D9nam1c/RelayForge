package io.relayforge.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import io.relayforge.contracts.AttemptCommand;
import io.relayforge.contracts.DeliveryPolicy;
import io.relayforge.contracts.EncryptedSecret;
import io.relayforge.contracts.EndpointSnapshot;
import io.relayforge.contracts.EventEnvelope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class AttemptOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final StreamBridge stream;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    AttemptOutboxPublisher(JdbcTemplate jdbc, StreamBridge stream, TransactionTemplate transactions,
                           MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.stream = stream;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${relayforge.outbox.poll-delay-ms:200}")
    void publish() {
        transactions.executeWithoutResult(status -> {
            List<OutboxCommand> commands = jdbc.query("""
                    select o.id outbox_id, o.command_id, o.generation outbox_generation,
                      o.attempt_number outbox_attempt, d.*
                    from attempt_outbox o join deliveries d on d.id = o.delivery_id
                    where o.published_at is null order by o.created_at limit 100
                    for update of o skip locked
                    """, (rs, row) -> map(rs));
            for (OutboxCommand item : commands) {
                AttemptCommand command = item.command();
                boolean sent = stream.send("attempt-commands-out-0", MessageBuilder.withPayload(command)
                        .setHeader("kafka_messageKey", command.endpoint().endpointId().toString().getBytes())
                        .setHeader("relayforge_message_id", command.commandId().toString()).build());
                if (!sent) throw new IllegalStateException("Kafka binder rejected attempt command");
                jdbc.update("update attempt_outbox set published_at = now() where id = ?", item.outboxId());
            }
            if (!commands.isEmpty()) metrics.counter("relayforge.attempt.outbox.published").increment(commands.size());
        });
    }

    private static OutboxCommand map(ResultSet rs) throws SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        EventEnvelope event = new EventEnvelope(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                rs.getInt("schema_version"), tenantId, rs.getString("source_id"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class), rs.getString("traceparent"), rs.getString("content_type"),
                rs.getBytes("payload"));
        DeliveryPolicy policy = new DeliveryPolicy(rs.getInt("max_attempts"),
                Duration.ofMillis(rs.getLong("initial_delay_ms")), Duration.ofMillis(rs.getLong("max_delay_ms")),
                Duration.ofMillis(rs.getLong("max_retry_after_ms")), rs.getInt("endpoint_concurrency"),
                rs.getBoolean("preserve_order"));
        EncryptedSecret previous = rs.getString("previous_key_id") == null ? null
                : new EncryptedSecret(rs.getString("previous_key_id"), rs.getString("previous_ciphertext"));
        EndpointSnapshot endpoint = new EndpointSnapshot(rs.getObject("endpoint_id", UUID.class), tenantId,
                rs.getString("endpoint_url"), policy,
                new EncryptedSecret(rs.getString("current_key_id"), rs.getString("current_ciphertext")), previous,
                rs.getTimestamp("previous_valid_until") == null ? null
                        : rs.getTimestamp("previous_valid_until").toInstant());
        AttemptCommand command = new AttemptCommand(rs.getObject("command_id", UUID.class),
                rs.getObject("id", UUID.class), rs.getInt("outbox_generation"), rs.getInt("outbox_attempt"),
                rs.getTimestamp("lease_expires_at").toInstant(), event, endpoint);
        return new OutboxCommand(rs.getObject("outbox_id", UUID.class), command);
    }

    private record OutboxCommand(UUID outboxId, AttemptCommand command) {}
}
