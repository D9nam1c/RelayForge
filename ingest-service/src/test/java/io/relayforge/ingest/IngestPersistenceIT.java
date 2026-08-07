package io.relayforge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class IngestPersistenceIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    static JdbcTemplate jdbc;
    static IngestService service;
    static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @BeforeAll
    static void database() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        service = new IngestService(jdbc, 1024, new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void idempotencyIsTenantScopedAndOutboxIsCreatedExactlyOnce() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        var first = accept(tenantA, "same-key", payload);
        var duplicate = accept(tenantA, "same-key", payload);
        var otherTenant = accept(tenantB, "same-key", payload);

        assertThat(duplicate.eventId()).isEqualTo(first.eventId());
        assertThat(duplicate.idempotencyHit()).isTrue();
        assertThat(otherTenant.eventId()).isNotEqualTo(first.eventId());
        assertThat(jdbc.queryForObject("select count(*) from events", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(2);
    }

    @Test
    void reusedKeyWithDifferentBytesIsRejected() {
        UUID tenant = UUID.randomUUID();
        accept(tenant, "conflict", "{\"amount\":1}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> accept(tenant, "conflict", "{\"amount\":2}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private static IngestService.AcceptedEvent accept(UUID tenant, String key, byte[] payload) {
        return service.accept(tenant, key, "invoice.paid", 1, "invoice-1", "application/json", payload,
                UUID.randomUUID(), null, null);
    }
}
