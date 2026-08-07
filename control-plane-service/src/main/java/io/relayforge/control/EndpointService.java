package io.relayforge.control;

import io.relayforge.contracts.DeliveryPolicy;
import io.relayforge.contracts.EncryptedSecret;
import io.relayforge.contracts.EndpointSnapshot;
import io.relayforge.contracts.SubscriptionMatch;
import io.relayforge.security.OutboundTargetPolicy;
import io.relayforge.security.SecretCipher;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class EndpointService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final OutboundTargetPolicy targets;
    private final Duration rotationOverlap;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    EndpointService(JdbcTemplate jdbc, SecretCipher cipher, OutboundTargetPolicy targets,
                    @Value("${relayforge.secrets.rotation-overlap:PT15M}") Duration rotationOverlap) {
        this(jdbc, cipher, targets, rotationOverlap, Clock.systemUTC());
    }

    EndpointService(JdbcTemplate jdbc, SecretCipher cipher, OutboundTargetPolicy targets,
                    Duration rotationOverlap, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.targets = targets;
        if (rotationOverlap.isNegative() || rotationOverlap.isZero()
                || rotationOverlap.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("rotation overlap must be positive and no more than 24 hours");
        }
        this.rotationOverlap = rotationOverlap;
        this.clock = clock;
    }

    @Transactional
    CreatedEndpoint create(UUID tenantId, String actor, CreateEndpoint request) {
        targets.validate(request.url());
        UUID id = UUID.randomUUID();
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String plaintext = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        EncryptedSecret encrypted = cipher.encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), id.toString());
        DeliveryPolicy policy = request.policy() == null ? DeliveryPolicy.defaults() : request.policy();
        Instant now = clock.instant();
        jdbc.update("""
                insert into endpoints(id, tenant_id, name, url, current_key_id, current_ciphertext,
                  max_attempts, initial_delay_ms, max_delay_ms, max_retry_after_ms, endpoint_concurrency,
                  preserve_order, enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                """, id, tenantId, request.name(), request.url(), encrypted.keyId(), encrypted.ciphertext(),
                policy.maxAttempts(), policy.initialDelay().toMillis(), policy.maxDelay().toMillis(),
                policy.maxRetryAfter().toMillis(), policy.endpointConcurrency(), policy.preserveOrder(), now, now);
        for (SubscriptionInput subscription : request.subscriptions()) {
            jdbc.update("""
                    insert into subscriptions(id, tenant_id, endpoint_id, event_type, filter_json, enabled, created_at)
                    values (?, ?, ?, ?, ?::jsonb, true, ?)
                    """, UUID.randomUUID(), tenantId, id, subscription.eventType(), subscription.filterJson(), now);
        }
        audit(tenantId, actor, "ENDPOINT_CREATED", "endpoint", id, "name=" + request.name());
        return new CreatedEndpoint(id, tenantId, request.name(), request.url(), plaintext, policy, now);
    }

    List<EndpointView> list(UUID tenantId) {
        return jdbc.query("""
                select id, tenant_id, name, url, enabled, max_attempts, initial_delay_ms, max_delay_ms,
                  max_retry_after_ms, endpoint_concurrency, preserve_order, previous_valid_until, created_at, updated_at
                from endpoints where tenant_id = ? order by created_at
                """, (rs, row) -> mapView(rs), tenantId);
    }

    @Transactional
    RotatedSecret rotate(UUID tenantId, UUID endpointId, String actor) {
        EndpointSecret existing;
        try {
            existing = jdbc.queryForObject("""
                    select current_key_id, current_ciphertext from endpoints where id = ? and tenant_id = ? for update
                    """, (rs, row) -> new EndpointSecret(rs.getString(1), rs.getString(2)), endpointId, tenantId);
        } catch (EmptyResultDataAccessException exception) {
            throw new NotFoundException("Endpoint not found");
        }
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        String plaintext = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        EncryptedSecret next = cipher.encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8), endpointId.toString());
        Instant now = clock.instant();
        Instant validUntil = now.plus(rotationOverlap);
        jdbc.update("""
                update endpoints set previous_key_id = ?, previous_ciphertext = ?, previous_valid_until = ?,
                  current_key_id = ?, current_ciphertext = ?, updated_at = ? where id = ? and tenant_id = ?
                """, existing.keyId(), existing.ciphertext(), validUntil, next.keyId(), next.ciphertext(), now,
                endpointId, tenantId);
        audit(tenantId, actor, "SECRET_ROTATED", "endpoint", endpointId, "overlapUntil=" + validUntil);
        return new RotatedSecret(endpointId, plaintext, validUntil);
    }

    List<SubscriptionMatch> matching(UUID tenantId, String eventType) {
        return jdbc.query("""
                select s.id subscription_id, s.filter_json::text, e.* from subscriptions s
                join endpoints e on e.id = s.endpoint_id and e.tenant_id = s.tenant_id
                where s.tenant_id = ? and s.event_type = ? and s.enabled and e.enabled
                order by s.id
                """, (rs, row) -> new SubscriptionMatch(rs.getObject("subscription_id", UUID.class),
                        mapSnapshot(rs), rs.getString("filter_json")), tenantId, eventType);
    }

    private EndpointSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        EncryptedSecret previous = rs.getString("previous_key_id") == null ? null
                : new EncryptedSecret(rs.getString("previous_key_id"), rs.getString("previous_ciphertext"));
        return new EndpointSnapshot(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("url"), policy(rs),
                new EncryptedSecret(rs.getString("current_key_id"), rs.getString("current_ciphertext")), previous,
                rs.getTimestamp("previous_valid_until") == null ? null
                        : rs.getTimestamp("previous_valid_until").toInstant());
    }

    private EndpointView mapView(ResultSet rs) throws SQLException {
        return new EndpointView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("url"), rs.getBoolean("enabled"), policy(rs),
                rs.getTimestamp("previous_valid_until") == null ? null
                        : rs.getTimestamp("previous_valid_until").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static DeliveryPolicy policy(ResultSet rs) throws SQLException {
        return new DeliveryPolicy(rs.getInt("max_attempts"), Duration.ofMillis(rs.getLong("initial_delay_ms")),
                Duration.ofMillis(rs.getLong("max_delay_ms")), Duration.ofMillis(rs.getLong("max_retry_after_ms")),
                rs.getInt("endpoint_concurrency"), rs.getBoolean("preserve_order"));
    }

    private void audit(UUID tenantId, String actor, String action, String resourceType, UUID resourceId, String detail) {
        jdbc.update("""
                insert into audit_log(id, tenant_id, actor, action, resource_type, resource_id, detail, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, actor, action, resourceType, resourceId, detail, clock.instant());
    }

    record CreateEndpoint(String name, String url, List<SubscriptionInput> subscriptions, DeliveryPolicy policy) {
        CreateEndpoint {
            if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("name is required");
            if (url == null || url.isBlank() || url.length() > 2048) throw new IllegalArgumentException("url is required");
            subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
            if (subscriptions.isEmpty()) throw new IllegalArgumentException("at least one subscription is required");
        }
    }
    record SubscriptionInput(String eventType, String filterJson) {
        SubscriptionInput {
            if (eventType == null || !eventType.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("invalid event type");
            }
            filterJson = filterJson == null || filterJson.isBlank() ? "{}" : filterJson;
            try {
                if (!JSON.readTree(filterJson).isObject()) throw new IllegalArgumentException("filter must be a JSON object");
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("filter must be a valid JSON object", invalid);
            }
        }
    }
    record CreatedEndpoint(UUID id, UUID tenantId, String name, String url, String signingSecret,
                           DeliveryPolicy policy, Instant createdAt) {}
    record EndpointView(UUID id, UUID tenantId, String name, String url, boolean enabled, DeliveryPolicy policy,
                        Instant previousSecretValidUntil, Instant createdAt, Instant updatedAt) {}
    record RotatedSecret(UUID endpointId, String signingSecret, Instant previousSecretValidUntil) {}
    private record EndpointSecret(String keyId, String ciphertext) {}
}
