package io.relayforge.sink;

import static org.assertj.core.api.Assertions.assertThat;

import io.relayforge.security.WebhookSigner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SinkRotationTest {
    private final Instant now = Instant.parse("2026-08-07T12:00:00Z");
    private final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    private final UUID messageId = UUID.randomUUID();

    @Test
    void currentAndPreviousKeysWorkOnlyDuringOverlap() {
        SinkRegistry active = new SinkRegistry(Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
        active.configure("rotating", new SinkRegistry.SinkEndpoint("new", "old", now.plusSeconds(60),
                SinkRegistry.Mode.SUCCESS, 0, 0, 0));
        SinkController controller = new SinkController(active);
        String oldSignature = WebhookSigner.sign("old".getBytes(StandardCharsets.UTF_8), messageId, now, body);
        String newSignature = WebhookSigner.sign("new".getBytes(StandardCharsets.UTF_8), messageId, now, body);

        assertThat(controller.receive("rotating", messageId, now.getEpochSecond(), 1, "test", oldSignature, body)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.receive("rotating", messageId, now.getEpochSecond(), 1, "test", newSignature, body)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Instant later = now.plusSeconds(61);
        SinkRegistry expired = new SinkRegistry(Duration.ofMinutes(5), Clock.fixed(later, ZoneOffset.UTC));
        expired.configure("rotating", new SinkRegistry.SinkEndpoint("new", "old", now.plusSeconds(60),
                SinkRegistry.Mode.SUCCESS, 0, 0, 0));
        SinkController expiredController = new SinkController(expired);
        String retiredSignature = WebhookSigner.sign("old".getBytes(StandardCharsets.UTF_8), messageId, later, body);
        assertThat(expiredController.receive("rotating", messageId, later.getEpochSecond(), 1, "test", retiredSignature,
                body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
