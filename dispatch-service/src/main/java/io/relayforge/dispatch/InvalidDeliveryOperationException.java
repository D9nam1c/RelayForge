package io.relayforge.dispatch;

final class InvalidDeliveryOperationException extends RuntimeException {
    InvalidDeliveryOperationException(String message) {
        super(message);
    }
}
