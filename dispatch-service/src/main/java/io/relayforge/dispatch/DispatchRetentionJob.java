package io.relayforge.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class DispatchRetentionJob {
    private final JdbcTemplate jdbc;
    private final Duration retention;
    private final MeterRegistry metrics;

    DispatchRetentionJob(JdbcTemplate jdbc, @Value("${relayforge.payload.retention:P30D}") Duration retention,
                         MeterRegistry metrics) {
        this.jdbc = jdbc; this.retention = retention; this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${relayforge.retention.poll-ms:3600000}")
    void purgeTerminalPayloadsAndKeys() {
        int purged = jdbc.update("""
                update deliveries set payload = '\\x', current_key_id = null, current_ciphertext = null,
                  previous_key_id = null, previous_ciphertext = null, previous_valid_until = null,
                  payload_purged_at = now(), updated_at = now()
                where state in ('SUCCEEDED','DEAD_LETTERED','CANCELLED') and payload_purged_at is null
                  and updated_at < now() - (? * interval '1 millisecond')
                """, retention.toMillis());
        if (purged > 0) metrics.counter("relayforge.payloads.purged", "store", "dispatch").increment(purged);
    }
}
