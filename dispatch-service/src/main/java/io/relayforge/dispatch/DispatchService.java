package io.relayforge.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import io.relayforge.contracts.AttemptOutcome;
import io.relayforge.contracts.AttemptResult;
import io.relayforge.contracts.DeliveryPolicy;
import io.relayforge.contracts.EventEnvelope;
import io.relayforge.contracts.SubscriptionMatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class DispatchService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ControlPlaneClient controlPlane;
    private final PayloadFilter filters;
    private final RetryPlanner retries;
    private final MeterRegistry metrics;
    private final Clock clock;

    DispatchService(JdbcTemplate jdbc, TransactionTemplate transactions, ControlPlaneClient controlPlane,
                    PayloadFilter filters, RetryPlanner retries, MeterRegistry metrics) {
        this(jdbc, transactions, controlPlane, filters, retries, metrics, Clock.systemUTC());
    }

    DispatchService(JdbcTemplate jdbc, TransactionTemplate transactions, ControlPlaneClient controlPlane,
                    PayloadFilter filters, RetryPlanner retries, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.controlPlane = controlPlane;
        this.filters = filters;
        this.retries = retries;
        this.metrics = metrics;
        this.clock = clock;
    }

    void fanOut(EventEnvelope event) {
        List<SubscriptionMatch> matches = controlPlane.matching(event.tenantId(), event.type()).stream()
                .filter(match -> filters.matches(match.filterJson(), event.payload()))
                .toList();
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.update("""
                    insert into event_inbox(message_id, tenant_id, received_at) values (?, ?, ?)
                    on conflict (message_id) do nothing
                    """, event.messageId(), event.tenantId(), clock.instant());
            if (inserted == 0) {
                metrics.counter("relayforge.dispatch.inbox.duplicates").increment();
                return;
            }
            for (SubscriptionMatch match : matches) insertDelivery(event, match);
            metrics.counter("relayforge.dispatch.fanout").increment(matches.size());
        });
    }

    void recordResult(AttemptResult result) {
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.update("""
                    insert into result_inbox(result_id, received_at) values (?, ?) on conflict (result_id) do nothing
                    """, result.resultId(), clock.instant());
            if (inserted == 0) {
                metrics.counter("relayforge.dispatch.result.duplicates").increment();
                return;
            }
            DeliveryForResult delivery = jdbc.queryForObject("""
                    select tenant_id, state, generation, attempt_count, max_attempts, initial_delay_ms,
                      max_delay_ms, max_retry_after_ms, endpoint_concurrency, preserve_order
                    from deliveries where id = ? for update
                    """, (rs, row) -> mapForResult(rs), result.deliveryId());
            if (delivery == null || !delivery.tenantId().equals(result.tenantId())) {
                throw new IllegalArgumentException("Attempt result does not belong to delivery tenant");
            }
            boolean current = delivery.state() == DeliveryState.IN_FLIGHT
                    && delivery.generation() == result.executionGeneration()
                    && delivery.attemptCount() == result.attemptNumber();
            insertAttempt(result, !current);
            if (!current) {
                metrics.counter("relayforge.delivery.results", "outcome", "stale").increment();
                return;
            }
            if (result.outcome() == AttemptOutcome.SUCCEEDED) {
                transition(result.deliveryId(), DeliveryState.IN_FLIGHT, DeliveryState.SUCCEEDED, null, null,
                        result.completedAt());
            } else if (!RetryPlanner.retryable(result.outcome())) {
                transition(result.deliveryId(), DeliveryState.IN_FLIGHT, DeliveryState.DEAD_LETTERED, null,
                        terminalReason(result), result.completedAt());
            } else if (result.attemptNumber() >= delivery.maxAttempts()) {
                transition(result.deliveryId(), DeliveryState.IN_FLIGHT, DeliveryState.DEAD_LETTERED, null,
                        "attempts_exhausted:" + result.outcome().name().toLowerCase(), result.completedAt());
            } else {
                Instant next = retries.nextAttempt(delivery.policy(), result.attemptNumber(), result.completedAt(),
                        result.retryAfter());
                transition(result.deliveryId(), DeliveryState.IN_FLIGHT, DeliveryState.RETRY_WAIT, next, null,
                        result.completedAt());
                metrics.counter("relayforge.delivery.retries", "reason", result.outcome().name().toLowerCase()).increment();
            }
            metrics.counter("relayforge.delivery.attempts", "outcome", result.outcome().name().toLowerCase()).increment();
            metrics.timer("relayforge.delivery.outbound.latency", "outcome", result.outcome().name().toLowerCase())
                    .record(result.latency());
        });
    }

    List<DeliveryView> list(UUID tenantId, UUID eventId) {
        String sql = """
                select id, event_id, endpoint_id, event_type, state, generation, attempt_count, next_attempt_at,
                  terminal_reason, replay_count, created_at, updated_at
                from deliveries where tenant_id = ?
                """ + (eventId == null ? "" : " and event_id = ?") + " order by created_at desc limit 500";
        Object[] args = eventId == null ? new Object[] {tenantId} : new Object[] {tenantId, eventId};
        return jdbc.query(sql, (rs, row) -> mapView(rs), args);
    }

    DeliveryDetail get(UUID tenantId, UUID deliveryId) {
        List<DeliveryView> delivery = jdbc.query("""
                select id, event_id, endpoint_id, event_type, state, generation, attempt_count, next_attempt_at,
                  terminal_reason, replay_count, created_at, updated_at
                from deliveries where tenant_id = ? and id = ?
                """, (rs, row) -> mapView(rs), tenantId, deliveryId);
        if (delivery.isEmpty()) throw new DispatchNotFoundException();
        List<AttemptView> attempts = jdbc.query("""
                select generation, attempt_number, outcome, http_status, latency_ms, completed_at, retry_after,
                  response_bytes, detail, late_result from delivery_attempts
                where delivery_id = ? order by generation, attempt_number, completed_at
                """, (rs, row) -> new AttemptView(rs.getInt("generation"), rs.getInt("attempt_number"),
                AttemptOutcome.valueOf(rs.getString("outcome")), (Integer) rs.getObject("http_status"),
                rs.getLong("latency_ms"), rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("retry_after") == null ? null : rs.getTimestamp("retry_after").toInstant(),
                rs.getLong("response_bytes"), rs.getString("detail"), rs.getBoolean("late_result")), deliveryId);
        return new DeliveryDetail(delivery.getFirst(), attempts);
    }

    void replay(UUID tenantId, UUID deliveryId, String actor) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    update deliveries set state = 'SCHEDULED', generation = generation + 1, attempt_count = 0,
                      next_attempt_at = ?, terminal_reason = null, lease_command_id = null, lease_expires_at = null,
                      replay_count = replay_count + 1, updated_at = ?
                    where id = ? and tenant_id = ? and state = 'DEAD_LETTERED' and payload_purged_at is null
                    """, clock.instant(), clock.instant(), deliveryId, tenantId);
            if (changed == 0) throw new InvalidDeliveryOperationException("Only dead-lettered deliveries can be replayed");
            audit(tenantId, actor, "DELIVERY_REPLAYED", deliveryId);
            metrics.counter("relayforge.delivery.replays").increment();
        });
    }

    void cancel(UUID tenantId, UUID deliveryId, String actor) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    update deliveries set state = 'CANCELLED', terminal_reason = 'operator_cancelled', updated_at = ?
                    where id = ? and tenant_id = ? and state in ('PENDING','SCHEDULED','RETRY_WAIT')
                    """, clock.instant(), deliveryId, tenantId);
            if (changed == 0) throw new InvalidDeliveryOperationException("Delivery cannot be cancelled in its current state");
            audit(tenantId, actor, "DELIVERY_CANCELLED", deliveryId);
        });
    }

    private void insertDelivery(EventEnvelope event, SubscriptionMatch match) {
        var endpoint = match.endpoint();
        var policy = endpoint.policy();
        UUID deliveryId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into deliveries(id, tenant_id, event_id, subscription_id, endpoint_id, endpoint_url,
                  event_type, schema_version, source_id, occurred_at, correlation_id, causation_id, traceparent,
                  content_type, payload, current_key_id, current_ciphertext, previous_key_id, previous_ciphertext,
                  previous_valid_until, max_attempts, initial_delay_ms, max_delay_ms, max_retry_after_ms,
                  endpoint_concurrency, preserve_order, state, generation, attempt_count, next_attempt_at,
                  replay_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  'SCHEDULED', 0, 0, ?, 0, ?, ?)
                on conflict (event_id, subscription_id) do nothing
                """, deliveryId, event.tenantId(), event.messageId(), match.subscriptionId(), endpoint.endpointId(),
                endpoint.url(), event.type(), event.schemaVersion(), event.sourceId(), event.occurredAt(),
                event.correlationId(), event.causationId(), event.traceparent(), event.payloadContentType(),
                event.payload(), endpoint.currentSecret().keyId(), endpoint.currentSecret().ciphertext(),
                endpoint.previousSecret() == null ? null : endpoint.previousSecret().keyId(),
                endpoint.previousSecret() == null ? null : endpoint.previousSecret().ciphertext(),
                endpoint.previousSecretValidUntil(), policy.maxAttempts(), policy.initialDelay().toMillis(),
                policy.maxDelay().toMillis(), policy.maxRetryAfter().toMillis(), policy.endpointConcurrency(),
                policy.preserveOrder(), now, now, now);
    }

    private void transition(UUID id, DeliveryState from, DeliveryState to, Instant next, String reason, Instant updated) {
        DeliveryStateMachine.requireTransition(from, to);
        int changed = jdbc.update("""
                update deliveries set state = ?, next_attempt_at = ?, terminal_reason = ?, lease_command_id = null,
                  lease_expires_at = null, updated_at = ? where id = ? and state = ?
                """, to.name(), next, reason, updated, id, from.name());
        if (changed != 1) throw new IllegalStateException("Concurrent delivery state change");
    }

    private void insertAttempt(AttemptResult result, boolean late) {
        jdbc.update("""
                insert into delivery_attempts(id, delivery_id, generation, attempt_number, outcome, http_status,
                  latency_ms, completed_at, retry_after, response_bytes, detail, late_result)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (delivery_id, generation, attempt_number) do nothing
                """, result.resultId(), result.deliveryId(), result.executionGeneration(), result.attemptNumber(),
                result.outcome().name(), result.httpStatus(), result.latency().toMillis(), result.completedAt(),
                result.retryAfter(), result.responseBytes(), result.detail(), late);
    }

    private void audit(UUID tenantId, String actor, String action, UUID deliveryId) {
        jdbc.update("""
                insert into dispatch_audit(id, tenant_id, actor, action, delivery_id, occurred_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actor, action, deliveryId, clock.instant());
    }

    private static String terminalReason(AttemptResult result) {
        return result.outcome().name().toLowerCase() + (result.httpStatus() == null ? "" : ":http_" + result.httpStatus());
    }

    private static DeliveryForResult mapForResult(ResultSet rs) throws SQLException {
        return new DeliveryForResult(rs.getObject("tenant_id", UUID.class), DeliveryState.valueOf(rs.getString("state")),
                rs.getInt("generation"), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                new DeliveryPolicy(rs.getInt("max_attempts"), java.time.Duration.ofMillis(rs.getLong("initial_delay_ms")),
                        java.time.Duration.ofMillis(rs.getLong("max_delay_ms")),
                        java.time.Duration.ofMillis(rs.getLong("max_retry_after_ms")),
                        rs.getInt("endpoint_concurrency"), rs.getBoolean("preserve_order")));
    }

    private static DeliveryView mapView(ResultSet rs) throws SQLException {
        return new DeliveryView(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getString("event_type"),
                DeliveryState.valueOf(rs.getString("state")), rs.getInt("generation"), rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getString("terminal_reason"), rs.getInt("replay_count"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private record DeliveryForResult(UUID tenantId, DeliveryState state, int generation, int attemptCount,
                                     int maxAttempts, DeliveryPolicy policy) {}
    record DeliveryView(UUID deliveryId, UUID eventId, UUID endpointId, String eventType, DeliveryState state,
                        int executionGeneration, int attemptCount, Instant nextAttemptAt, String terminalReason,
                        int replayCount, Instant createdAt, Instant updatedAt) {}
    record AttemptView(int executionGeneration, int attemptNumber, AttemptOutcome outcome, Integer httpStatus,
                       long latencyMs, Instant completedAt, Instant retryAfter, long responseBytes, String detail,
                       boolean lateResult) {}
    record DeliveryDetail(DeliveryView delivery, List<AttemptView> attempts) {}
}
