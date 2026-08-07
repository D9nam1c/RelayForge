package io.relayforge.control;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SecretRetirementJob {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    SecretRetirementJob(JdbcTemplate jdbc, MeterRegistry metrics) { this.jdbc = jdbc; this.metrics = metrics; }

    @Scheduled(fixedDelayString = "${relayforge.retention.secret-retirement-ms:60000}")
    void retireExpiredPreviousSecrets() {
        int retired = jdbc.update("""
                update endpoints set previous_key_id = null, previous_ciphertext = null, previous_valid_until = null,
                  updated_at = now() where previous_valid_until < now()
                """);
        if (retired > 0) metrics.counter("relayforge.secrets.previous.retired").increment(retired);
    }
}
