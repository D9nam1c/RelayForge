package io.relayforge.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdempotencyFingerprintTest {
    @Test
    void fingerprintBindsMetadataAndExactPayload() {
        byte[] payload = "{\"amount\":42}".getBytes(StandardCharsets.UTF_8);
        String original = IngestService.fingerprint("invoice.paid", 1, "invoice-7", "application/json", payload);

        assertThat(IngestService.fingerprint("invoice.paid", 1, "invoice-7", "application/json", payload))
                .isEqualTo(original);
        assertThat(IngestService.fingerprint("invoice.failed", 1, "invoice-7", "application/json", payload))
                .isNotEqualTo(original);
        assertThat(IngestService.fingerprint("invoice.paid", 1, "invoice-7", "application/json",
                "{\"amount\":43}".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(original);
    }
}
