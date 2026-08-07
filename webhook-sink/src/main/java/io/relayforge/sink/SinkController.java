package io.relayforge.sink;

import io.relayforge.security.WebhookSigner;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SinkController {
    private final SinkRegistry registry;

    SinkController(SinkRegistry registry) { this.registry = registry; }

    @PutMapping("/test/endpoints/{endpointId}")
    void configure(@PathVariable String endpointId, @RequestBody SinkRegistry.SinkEndpoint endpoint) {
        registry.configure(endpointId, endpoint);
    }

    @GetMapping("/test/timeline")
    List<SinkRegistry.SinkAttempt> timeline() { return registry.timeline(); }

    @PostMapping("/webhooks/{endpointId}")
    ResponseEntity<Void> receive(
            @PathVariable String endpointId,
            @RequestHeader("Webhook-Id") UUID messageId,
            @RequestHeader("Webhook-Timestamp") long timestampSeconds,
            @RequestHeader("Webhook-Attempt") int attempt,
            @RequestHeader("Webhook-Event-Type") String eventType,
            @RequestHeader("Webhook-Signature") String signature,
            @RequestBody byte[] body) {
        SinkRegistry.SinkEndpoint endpoint = registry.endpoint(endpointId);
        if (endpoint == null) return response(endpointId, messageId, attempt, eventType, HttpStatus.NOT_FOUND);
        Instant timestamp = Instant.ofEpochSecond(timestampSeconds);
        if (!registry.timestampAllowed(timestamp) || !validSignature(endpoint, messageId, timestamp, body, signature)) {
            return response(endpointId, messageId, attempt, eventType, HttpStatus.UNAUTHORIZED);
        }
        HttpStatus status = evaluate(endpointId, messageId, attempt, endpoint);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", Long.toString(endpoint.retryAfterSeconds()));
            registry.record(new SinkRegistry.SinkAttempt(endpointId, messageId, attempt, eventType, status.value(), registry.now()));
            return new ResponseEntity<>(headers, status);
        }
        return response(endpointId, messageId, attempt, eventType, status);
    }

    private boolean validSignature(SinkRegistry.SinkEndpoint endpoint, UUID messageId, Instant timestamp, byte[] body,
                                   String signature) {
        if (WebhookSigner.verify(endpoint.currentSecret().getBytes(StandardCharsets.UTF_8), messageId, timestamp, body,
                signature)) return true;
        return endpoint.previousSecret() != null && !registry.now().isAfter(endpoint.previousValidUntil())
                && WebhookSigner.verify(endpoint.previousSecret().getBytes(StandardCharsets.UTF_8), messageId,
                timestamp, body, signature);
    }

    private HttpStatus evaluate(String endpointId, UUID messageId, int attempt, SinkRegistry.SinkEndpoint endpoint) {
        return switch (endpoint.mode()) {
            case SUCCESS -> HttpStatus.NO_CONTENT;
            case FLAKY -> attempt <= endpoint.failuresBeforeSuccess()
                    ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.NO_CONTENT;
            case RATE_LIMIT -> attempt <= endpoint.failuresBeforeSuccess()
                    ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.NO_CONTENT;
            case ALWAYS_500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case DUPLICATE_SENSITIVE -> registry.firstSeen(endpointId, messageId)
                    ? HttpStatus.NO_CONTENT : HttpStatus.CONFLICT;
            case TIMEOUT, DELAYED_SUCCESS -> {
                try { Thread.sleep(endpoint.delayMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); yield HttpStatus.SERVICE_UNAVAILABLE; }
                yield HttpStatus.NO_CONTENT;
            }
        };
    }

    private ResponseEntity<Void> response(String endpointId, UUID messageId, int attempt, String eventType,
                                          HttpStatus status) {
        registry.record(new SinkRegistry.SinkAttempt(endpointId, messageId, attempt, eventType, status.value(), registry.now()));
        return ResponseEntity.status(status).build();
    }
}
