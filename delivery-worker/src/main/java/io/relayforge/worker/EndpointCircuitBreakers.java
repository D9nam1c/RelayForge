package io.relayforge.worker;

import io.relayforge.contracts.AttemptCommand;
import io.relayforge.contracts.AttemptOutcome;
import io.relayforge.contracts.AttemptResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

/** Uses 4096 stable endpoint groups: bounded metrics/cardinality without a single global customer circuit. */
@Component
class EndpointCircuitBreakers {
    private final CircuitBreakerFactory<?, ?> factory;

    EndpointCircuitBreakers(CircuitBreakerFactory<?, ?> factory) { this.factory = factory; }

    AttemptResult execute(AttemptCommand command, Supplier<AttemptResult> action) {
        String group = "endpoint-group-" + Integer.toHexString(command.endpoint().endpointId().hashCode() & 0xfff);
        return factory.create(group).run(() -> {
            AttemptResult result = action.get();
            if (remoteFailure(result.outcome())) throw new RemoteFailure(result);
            return result;
        }, failure -> {
            if (failure instanceof RemoteFailure remote) return remote.result;
            Instant now = Instant.now();
            return new AttemptResult(UUID.randomUUID(), command.commandId(), command.deliveryId(),
                    command.event().tenantId(), command.executionGeneration(), command.attemptNumber(),
                    AttemptOutcome.ENDPOINT_SATURATED, null, Duration.ZERO, now, null, 0,
                    "endpoint circuit is open");
        });
    }

    private static boolean remoteFailure(AttemptOutcome outcome) {
        return switch (outcome) {
            case RATE_LIMITED, RETRYABLE_HTTP, TIMEOUT, CONNECTION_FAILURE, RESPONSE_TOO_LARGE -> true;
            default -> false;
        };
    }

    private static final class RemoteFailure extends RuntimeException {
        private final AttemptResult result;
        private RemoteFailure(AttemptResult result) { this.result = result; }
    }
}
