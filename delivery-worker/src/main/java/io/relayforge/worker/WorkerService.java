package io.relayforge.worker;

import io.micrometer.core.instrument.MeterRegistry;
import io.relayforge.contracts.AttemptCommand;
import io.relayforge.contracts.AttemptOutcome;
import io.relayforge.contracts.AttemptResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class WorkerService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WebhookHttpExecutor executor;
    private final RedisEndpointLimiter limiter;
    private final EndpointCircuitBreakers circuitBreakers;
    private final Duration processingLease;
    private final MeterRegistry metrics;
    private final Clock clock = Clock.systemUTC();

    WorkerService(JdbcTemplate jdbc, TransactionTemplate transactions, WebhookHttpExecutor executor,
                  RedisEndpointLimiter limiter, EndpointCircuitBreakers circuitBreakers,
                  @Value("${relayforge.worker.processing-lease:PT30S}") Duration processingLease,
                  MeterRegistry metrics) {
        this.jdbc = jdbc; this.transactions = transactions; this.executor = executor; this.limiter = limiter;
        this.circuitBreakers = circuitBreakers;
        this.processingLease = processingLease; this.metrics = metrics;
    }

    void handle(AttemptCommand command) {
        String workerLease = UUID.randomUUID().toString();
        boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> claim(command, workerLease)));
        if (!claimed) {
            metrics.counter("relayforge.worker.command.duplicates").increment();
            return;
        }
        RedisEndpointLimiter.Permit permit = null;
        AttemptResult result;
        try {
            permit = limiter.tryAcquire(command.endpoint().endpointId(), command.endpoint().policy().endpointConcurrency(),
                    processingLease.plusSeconds(5));
            result = permit == null ? saturated(command)
                    : circuitBreakers.execute(command, () -> executor.execute(command));
        } catch (RuntimeException dependencyFailure) {
            result = internalFailure(command, "concurrency limiter unavailable");
        } finally {
            if (permit != null) {
                try { limiter.release(permit); }
                catch (RuntimeException ignored) { metrics.counter("relayforge.worker.permit.release.failures").increment(); }
            }
        }
        AttemptResult completed = result;
        transactions.executeWithoutResult(status -> persistResult(command, workerLease, completed));
        metrics.counter("relayforge.worker.attempts", "outcome", result.outcome().name().toLowerCase()).increment();
    }

    private boolean claim(AttemptCommand command, String owner) {
        Instant now = clock.instant();
        jdbc.update("""
                insert into worker_commands(command_id, delivery_id, generation, attempt_number, status, received_at)
                values (?, ?, ?, ?, 'RECEIVED', ?) on conflict (command_id) do nothing
                """, command.commandId(), command.deliveryId(), command.executionGeneration(), command.attemptNumber(), now);
        return jdbc.update("""
                update worker_commands set lease_owner = ?, lease_until = ?
                where command_id = ? and status = 'RECEIVED' and (lease_until is null or lease_until < ?)
                """, owner, now.plus(processingLease), command.commandId(), now) == 1;
    }

    private void persistResult(AttemptCommand command, String owner, AttemptResult result) {
        int changed = jdbc.update("""
                update worker_commands set status = 'COMPLETED', result_id = ?, completed_at = ?, lease_owner = null,
                  lease_until = null where command_id = ? and status = 'RECEIVED' and lease_owner = ?
                """, result.resultId(), result.completedAt(), command.commandId(), owner);
        if (changed != 1) throw new IllegalStateException("Worker command lease was lost");
        jdbc.update("""
                insert into result_outbox(result_id, command_id, delivery_id, tenant_id, generation, attempt_number,
                  outcome, http_status, latency_ms, completed_at, retry_after, response_bytes, detail, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, result.resultId(), result.commandId(), result.deliveryId(), result.tenantId(),
                result.executionGeneration(), result.attemptNumber(), result.outcome().name(), result.httpStatus(),
                result.latency().toMillis(), result.completedAt(), result.retryAfter(), result.responseBytes(),
                result.detail(), clock.instant());
    }

    private AttemptResult saturated(AttemptCommand command) {
        return simpleResult(command, AttemptOutcome.ENDPOINT_SATURATED, "endpoint concurrency is saturated");
    }

    private AttemptResult internalFailure(AttemptCommand command, String detail) {
        return simpleResult(command, AttemptOutcome.INTERNAL_FAILURE, detail);
    }

    private AttemptResult simpleResult(AttemptCommand command, AttemptOutcome outcome, String detail) {
        Instant now = clock.instant();
        return new AttemptResult(UUID.randomUUID(), command.commandId(), command.deliveryId(), command.event().tenantId(),
                command.executionGeneration(), command.attemptNumber(), outcome, null, Duration.ZERO, now, null, 0,
                detail);
    }
}
