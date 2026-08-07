package io.relayforge.dispatch;

import java.util.List;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
class ControlPlaneFallbackFactory implements FallbackFactory<ControlPlaneClient> {
    @Override
    public ControlPlaneClient create(Throwable cause) {
        return (tenantId, eventType) -> { throw new SubscriptionResolutionUnavailableException(cause); };
    }
}

final class SubscriptionResolutionUnavailableException extends RuntimeException {
    SubscriptionResolutionUnavailableException(Throwable cause) {
        super("Control plane unavailable; event will be redelivered from Kafka", cause);
    }
}
