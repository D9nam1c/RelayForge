package io.relayforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {
    @Test
    void verifiesExactBytesAndBoundIdentifiers() {
        byte[] key = "a-long-enough-test-secret".getBytes(StandardCharsets.UTF_8);
        UUID id = UUID.randomUUID();
        Instant timestamp = Instant.ofEpochSecond(1_700_000_000);
        byte[] payload = "{\"amount\":42}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSigner.sign(key, id, timestamp, payload);

        assertThat(WebhookSigner.verify(key, id, timestamp, payload, signature)).isTrue();
        assertThat(WebhookSigner.verify(key, UUID.randomUUID(), timestamp, payload, signature)).isFalse();
        assertThat(WebhookSigner.verify(key, id, timestamp, "{}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(WebhookSigner.verify(key, id, timestamp, payload, "v1=not-base64!")).isFalse();
    }
}
