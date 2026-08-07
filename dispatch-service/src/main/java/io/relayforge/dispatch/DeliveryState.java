package io.relayforge.dispatch;

enum DeliveryState {
    PENDING,
    SCHEDULED,
    IN_FLIGHT,
    SUCCEEDED,
    RETRY_WAIT,
    DEAD_LETTERED,
    CANCELLED
}
