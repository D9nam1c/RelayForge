package io.relayforge.dispatch;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

final class DeliveryStateMachine {
    private static final Map<DeliveryState, EnumSet<DeliveryState>> ALLOWED = new EnumMap<>(DeliveryState.class);
    static {
        ALLOWED.put(DeliveryState.PENDING, EnumSet.of(DeliveryState.SCHEDULED, DeliveryState.CANCELLED));
        ALLOWED.put(DeliveryState.SCHEDULED, EnumSet.of(DeliveryState.IN_FLIGHT, DeliveryState.CANCELLED));
        ALLOWED.put(DeliveryState.IN_FLIGHT, EnumSet.of(DeliveryState.SUCCEEDED, DeliveryState.RETRY_WAIT,
                DeliveryState.DEAD_LETTERED));
        ALLOWED.put(DeliveryState.RETRY_WAIT, EnumSet.of(DeliveryState.IN_FLIGHT, DeliveryState.CANCELLED));
        ALLOWED.put(DeliveryState.DEAD_LETTERED, EnumSet.of(DeliveryState.SCHEDULED));
        ALLOWED.put(DeliveryState.SUCCEEDED, EnumSet.noneOf(DeliveryState.class));
        ALLOWED.put(DeliveryState.CANCELLED, EnumSet.noneOf(DeliveryState.class));
    }

    private DeliveryStateMachine() {}

    static void requireTransition(DeliveryState from, DeliveryState to) {
        if (!ALLOWED.get(from).contains(to)) {
            throw new IllegalStateException("Invalid delivery transition " + from + " -> " + to);
        }
    }
}
