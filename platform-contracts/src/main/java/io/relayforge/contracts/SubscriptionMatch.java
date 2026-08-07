package io.relayforge.contracts;

import java.util.Objects;
import java.util.UUID;

public record SubscriptionMatch(UUID subscriptionId, EndpointSnapshot endpoint, String filterJson) {
    public SubscriptionMatch {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(endpoint, "endpoint");
        filterJson = filterJson == null || filterJson.isBlank() ? "{}" : filterJson;
    }
}
