package io.relayforge.control;

final class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
        super(message);
    }
}
