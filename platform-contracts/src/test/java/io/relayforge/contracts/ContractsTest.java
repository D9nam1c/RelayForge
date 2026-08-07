package io.relayforge.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractsTest {
    @Test
    void eventEnvelopeDefensivelyCopiesPayload() {
        byte[] payload = "hello".getBytes();
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "invoice.paid", 1, UUID.randomUUID(),
                "invoice-1", Instant.now(), UUID.randomUUID(), null, null, "application/json", payload);

        payload[0] = 'x';
        byte[] read = event.payload();
        read[1] = 'x';

        assertThat(new String(event.payload())).isEqualTo("hello");
    }

    @Test
    void orderedPolicyMustBeSerial() {
        assertThatThrownBy(() -> new DeliveryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(1),
                Duration.ofMinutes(1), 2, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultSanitizesDiagnosticText() {
        AttemptResult result = new AttemptResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, 1, AttemptOutcome.INTERNAL_FAILURE, null, Duration.ZERO, Instant.now(), null,
                0, "line1\nline2" + "x".repeat(200));
        assertThat(result.detail()).doesNotContain("\n").hasSize(160);
    }
}
