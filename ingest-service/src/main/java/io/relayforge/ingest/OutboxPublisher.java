package io.relayforge.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import io.relayforge.contracts.EventEnvelope;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final StreamBridge stream;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final AtomicLong unpublished = new AtomicLong();
    private final Clock clock = Clock.systemUTC();

    OutboxPublisher(JdbcTemplate jdbc, StreamBridge stream, TransactionTemplate transactions, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.stream = stream;
        this.transactions = transactions;
        this.metrics = metrics;
        metrics.gauge("relayforge.outbox.unpublished", unpublished);
    }

    @Scheduled(fixedDelayString = "${relayforge.outbox.poll-delay-ms:250}")
    void publishBatch() {
        transactions.executeWithoutResult(status -> {
            List<OutboxEvent> rows = jdbc.query("""
                    select o.id outbox_id, e.* from outbox o join events e on e.id = o.event_id
                    where o.published_at is null order by o.created_at limit 100 for update of o skip locked
                    """, (rs, row) -> new OutboxEvent(rs.getObject("outbox_id", UUID.class), IngestService.mapEvent(rs)));
            for (OutboxEvent row : rows) {
                boolean sent = stream.send("accepted-events-out-0", MessageBuilder.withPayload(row.event())
                        .setHeader("kafka_messageKey", row.event().tenantId().toString().getBytes())
                        .setHeader("relayforge_message_id", row.event().messageId().toString())
                        .build());
                if (!sent) throw new IllegalStateException("Kafka binder rejected accepted event");
                jdbc.update("update outbox set published_at = ? where id = ?", clock.instant(), row.outboxId());
            }
            Long remaining = jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
            unpublished.set(remaining == null ? 0 : remaining);
        });
    }

    private record OutboxEvent(UUID outboxId, EventEnvelope event) {}
}
