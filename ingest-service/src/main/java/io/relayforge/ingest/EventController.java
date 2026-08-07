package io.relayforge.ingest;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
class EventController {
    private final IngestService service;

    EventController(IngestService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    IngestService.AcceptedEvent accept(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("Webhook-Event-Type") String eventType,
            @RequestHeader(value = "Webhook-Schema-Version", defaultValue = "1") int schemaVersion,
            @RequestHeader("Webhook-Source-Id") String sourceId,
            @RequestHeader(value = "Content-Type", defaultValue = "application/json") String contentType,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @RequestHeader(value = "X-Causation-Id", required = false) UUID causationId,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestBody byte[] payload,
            Authentication authentication) {
        UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
        return service.accept(tenant(authentication), idempotencyKey, eventType, schemaVersion, sourceId,
                contentType.split(";", 2)[0].trim(), payload, correlation, causationId, traceparent);
    }

    @GetMapping("/{eventId}")
    IngestService.EventView get(@PathVariable UUID eventId, Authentication authentication) {
        return service.find(tenant(authentication), eventId);
    }

    private static UUID tenant(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            try { return UUID.fromString(jwt.getClaimAsString("tenant_id")); }
            catch (RuntimeException ignored) { }
        }
        throw new AccessDeniedException("Valid tenant_id claim is required");
    }
}
