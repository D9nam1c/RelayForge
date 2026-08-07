package io.relayforge.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class DispatchOperationalMetrics {
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();
    private final AtomicLong dueBacklog = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong outbox = new AtomicLong();

    DispatchOperationalMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("relayforge.delivery.due.backlog", dueBacklog);
        registry.gauge("relayforge.delivery.queue.oldest.seconds", oldestSeconds);
        registry.gauge("relayforge.delivery.dead.letter.count", deadLetters);
        registry.gauge("relayforge.delivery.in.flight", inFlight);
        registry.gauge("relayforge.attempt.outbox.unpublished", outbox);
    }

    @Scheduled(fixedDelayString = "${relayforge.metrics.database-poll-ms:5000}")
    void refresh() {
        Instant now = clock.instant();
        dueBacklog.set(count("select count(*) from deliveries where state in ('SCHEDULED','RETRY_WAIT') and next_attempt_at <= now()"));
        deadLetters.set(count("select count(*) from deliveries where state = 'DEAD_LETTERED'"));
        inFlight.set(count("select count(*) from deliveries where state = 'IN_FLIGHT'"));
        outbox.set(count("select count(*) from attempt_outbox where published_at is null"));
        Instant oldest = jdbc.queryForObject("""
                select min(next_attempt_at) from deliveries
                where state in ('SCHEDULED','RETRY_WAIT') and next_attempt_at <= now()
                """, (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant());
        oldestSeconds.set(oldest == null ? 0 : Math.max(0, java.time.Duration.between(oldest, now).toSeconds()));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
