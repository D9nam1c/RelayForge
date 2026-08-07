package io.relayforge.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class IngestRetentionJob {
    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final MeterRegistry metrics;

    IngestRetentionJob(JdbcTemplate jdbc, @Value("${relayforge.payload.retention:P30D}") Duration retention,
                       MeterRegistry metrics) {
        this.jdbc = jdbc; this.retention = retention; this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${relayforge.retention.poll-ms:3600000}")
    void purgePayloads() {
        int purged = jdbc.update("""
                update events e set payload = '\\x', payload_purged_at = now()
                from outbox o where o.event_id = e.id and o.published_at is not null and e.payload_purged_at is null
                  and e.accepted_at < now() - (? * interval '1 millisecond')
                """, retention.toMillis());
        if (purged > 0) metrics.counter("relayforge.payloads.purged", "store", "ingest").increment(purged);
    }
}
