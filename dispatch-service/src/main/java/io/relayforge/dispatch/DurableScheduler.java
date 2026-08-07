package io.relayforge.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class DurableScheduler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SafeSchedulingSettings settings;
    private final MeterRegistry metrics;
    private final Clock clock = Clock.systemUTC();

    DurableScheduler(JdbcTemplate jdbc, TransactionTemplate transactions, SafeSchedulingSettings settings,
                     MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${relayforge.scheduling.poll-delay-ms:200}")
    void claimDue() {
        Long backlog = jdbc.queryForObject("select count(*) from attempt_outbox where published_at is null", Long.class);
        SafeSchedulingSettings.Values values = settings.current();
        if (backlog != null && backlog >= values.maxUnpublishedOutbox()) {
            metrics.counter("relayforge.scheduling.paused", "reason", "outbox_backlog").increment();
            return;
        }
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            List<UUID> ids = jdbc.query("""
                    with fair as (
                      select id, tenant_id, endpoint_id, next_attempt_at,
                        row_number() over (partition by tenant_id order by next_attempt_at, id) as tenant_rank
                      from deliveries
                      where state in ('SCHEDULED','RETRY_WAIT') and next_attempt_at <= ?
                    )
                    select d.id from deliveries d join fair f on f.id = d.id
                    where f.tenant_rank <= ?
                      and (select count(*) from deliveries t where t.tenant_id = d.tenant_id and t.state = 'IN_FLIGHT') < ?
                      and (select count(*) from deliveries e where e.endpoint_id = d.endpoint_id and e.state = 'IN_FLIGHT') < d.endpoint_concurrency
                    order by f.tenant_rank, f.next_attempt_at, d.id limit ?
                    for update of d skip locked
                    """, (rs, row) -> rs.getObject(1, UUID.class), now, values.perTenantBatch(),
                    values.tenantInFlight(), values.batchSize());
            for (UUID id : ids) {
                UUID commandId = UUID.randomUUID();
                Instant leaseExpires = now.plus(values.leaseDuration());
                int changed = jdbc.update("""
                        update deliveries set state = 'IN_FLIGHT', attempt_count = attempt_count + 1,
                          lease_command_id = ?, lease_expires_at = ?, updated_at = ?
                        where id = ? and state in ('SCHEDULED','RETRY_WAIT')
                        """, commandId, leaseExpires, now, id);
                if (changed == 1) {
                    jdbc.update("""
                            insert into attempt_outbox(id, command_id, delivery_id, generation, attempt_number, created_at)
                            select ?, ?, id, generation, attempt_count, ? from deliveries where id = ?
                            """, UUID.randomUUID(), commandId, now, id);
                }
            }
            if (!ids.isEmpty()) metrics.counter("relayforge.scheduling.claimed").increment(ids.size());
        });
    }

    @Scheduled(fixedDelayString = "${relayforge.scheduling.lease-recovery-ms:5000}")
    void recoverExpiredLeases() {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            List<ExpiredLease> expired = jdbc.query("""
                    select id, generation, attempt_count, max_attempts from deliveries
                    where state = 'IN_FLIGHT' and lease_expires_at < ? order by lease_expires_at
                    limit 100 for update skip locked
                    """, (rs, row) -> new ExpiredLease(rs.getObject("id", UUID.class), rs.getInt("generation"),
                    rs.getInt("attempt_count"), rs.getInt("max_attempts")), now);
            for (ExpiredLease lease : expired) {
                jdbc.update("""
                        insert into delivery_attempts(id, delivery_id, generation, attempt_number, outcome,
                          latency_ms, completed_at, response_bytes, detail, late_result)
                        values (?, ?, ?, ?, 'LEASE_EXPIRED', 0, ?, 0, 'worker lease expired; delivery outcome is ambiguous', false)
                        on conflict (delivery_id, generation, attempt_number) do nothing
                        """, UUID.randomUUID(), lease.id(), lease.generation(), lease.attempt(), now);
                if (lease.attempt() >= lease.maxAttempts()) {
                    jdbc.update("""
                            update deliveries set state = 'DEAD_LETTERED', terminal_reason = 'attempts_exhausted:lease_expired',
                              lease_command_id = null, lease_expires_at = null, updated_at = ? where id = ?
                            """, now, lease.id());
                } else {
                    jdbc.update("""
                            update deliveries set state = 'RETRY_WAIT', next_attempt_at = ?, lease_command_id = null,
                              lease_expires_at = null, updated_at = ? where id = ?
                            """, now, now, lease.id());
                }
            }
            if (!expired.isEmpty()) metrics.counter("relayforge.delivery.lease.recovered").increment(expired.size());
        });
    }

    private record ExpiredLease(UUID id, int generation, int attempt, int maxAttempts) {}
}
