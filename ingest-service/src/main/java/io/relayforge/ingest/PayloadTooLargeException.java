package io.relayforge.ingest;

final class PayloadTooLargeException extends RuntimeException {
    private final int maximum;

    PayloadTooLargeException(int maximum) {
        this.maximum = maximum;
    }

    int maximum() {
        return maximum;
    }
}
