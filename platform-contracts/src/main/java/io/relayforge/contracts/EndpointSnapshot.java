package io.relayforge.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EndpointSnapshot(
        UUID endpointId,
        UUID tenantId,
        String url,
        DeliveryPolicy policy,
        EncryptedSecret currentSecret,
        EncryptedSecret previousSecret,
        Instant previousSecretValidUntil) {
    public EndpointSnapshot {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(tenantId, "tenantId");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(currentSecret, "currentSecret");
        if ((previousSecret == null) != (previousSecretValidUntil == null)) {
            throw new IllegalArgumentException("previous secret and expiry must be present together");
        }
    }
}
