package io.relayforge.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.relayforge.contracts.DeliveryPolicy;
import io.relayforge.security.LocalAesGcmSecretCipher;
import io.relayforge.security.OutboundTargetPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    static JdbcTemplate jdbc;
    static EndpointService endpoints;
    static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeAll
    static void database() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl()); dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var cipher = new LocalAesGcmSecretCipher("test-v1", Base64.getEncoder().encodeToString(new byte[32]));
        var targets = new OutboundTargetPolicy(Set.of(443, 8090), true, "localhost");
        endpoints = new EndpointService(jdbc, cipher, targets, Duration.ofMinutes(15),
                Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void otherTenantCannotReadOrRotateEndpointAndPlaintextIsNotStored() {
        var created = endpoints.create(ACME, "acme-admin", new EndpointService.CreateEndpoint("billing",
                "http://localhost:8090/webhooks/acme", List.of(new EndpointService.SubscriptionInput("invoice.paid", "{}")),
                DeliveryPolicy.defaults()));

        assertThat(endpoints.list(ACME)).extracting(EndpointService.EndpointView::id).contains(created.id());
        assertThat(endpoints.list(GLOBEX)).isEmpty();
        assertThatThrownBy(() -> endpoints.rotate(GLOBEX, created.id(), "globex-admin"))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("select current_ciphertext from endpoints where id = ?", String.class, created.id()))
                .doesNotContain(created.signingSecret());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where tenant_id = ?", Long.class, ACME))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where tenant_id = ?", Long.class, GLOBEX))
                .isZero();
    }
}
