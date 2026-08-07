package io.relayforge.dispatch;

import io.relayforge.contracts.SubscriptionMatch;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "control-plane-service", fallbackFactory = ControlPlaneFallbackFactory.class)
interface ControlPlaneClient {
    @GetMapping("/internal/v1/subscriptions/matches")
    List<SubscriptionMatch> matching(@RequestParam UUID tenantId, @RequestParam String eventType);
}
