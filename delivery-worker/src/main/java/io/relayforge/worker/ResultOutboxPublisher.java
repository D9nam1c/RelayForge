package io.relayforge.worker;

import io.micrometer.core.instrument.MeterRegistry;
import io.relayforge.contracts.AttemptOutcome;
import io.relayforge.contracts.AttemptResult;
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
class ResultOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final StreamBridge stream;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    ResultOutboxPublisher(JdbcTemplate jdbc, StreamBridge stream, TransactionTemplate transactions,
                          MeterRegistry metrics) {
        this.jdbc = jdbc; this.stream = stream; this.transactions = transactions; this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${relayforge.outbox.poll-delay-ms:200}")
    void publish() {
        transactions.executeWithoutResult(status -> {
            List<AttemptResult> results = jdbc.query("""
                    select * from result_outbox where published_at is null order by created_at limit 100
                    for update skip locked
                    """, (rs, row) -> new AttemptResult(rs.getObject("result_id", UUID.class),
                    rs.getObject("command_id", UUID.class), rs.getObject("delivery_id", UUID.class),
                    rs.getObject("tenant_id", UUID.class), rs.getInt("generation"), rs.getInt("attempt_number"),
                    AttemptOutcome.valueOf(rs.getString("outcome")), (Integer) rs.getObject("http_status"),
                    Duration.ofMillis(rs.getLong("latency_ms")), rs.getTimestamp("completed_at").toInstant(),
                    rs.getTimestamp("retry_after") == null ? null : rs.getTimestamp("retry_after").toInstant(),
                    rs.getLong("response_bytes"), rs.getString("detail")));
            for (AttemptResult result : results) {
                boolean sent = stream.send("attempt-results-out-0", MessageBuilder.withPayload(result)
                        .setHeader("kafka_messageKey", result.deliveryId().toString().getBytes())
                        .setHeader("relayforge_message_id", result.resultId().toString()).build());
                if (!sent) throw new IllegalStateException("Kafka binder rejected attempt result");
                jdbc.update("update result_outbox set published_at = now() where result_id = ?", result.resultId());
            }
            if (!results.isEmpty()) metrics.counter("relayforge.worker.results.published").increment(results.size());
        });
    }
}
