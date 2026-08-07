package io.relayforge.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.relayforge.contracts.DeliveryPolicy;
import io.relayforge.contracts.EncryptedSecret;
import io.relayforge.contracts.EndpointSnapshot;
import io.relayforge.contracts.EventEnvelope;
import io.relayforge.contracts.SubscriptionMatch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class DispatchPersistenceIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    JdbcTemplate jdbc;
    DurableScheduler scheduler;

    @BeforeEach
    void database() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).locations("classpath:db/migration").load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("relayforge.scheduling.batch-size", "2")
                .withProperty("relayforge.scheduling.per-tenant-batch", "1")
                .withProperty("relayforge.scheduling.pause-outbox-backlog", "100");
        scheduler = new DurableScheduler(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new SafeSchedulingSettings(environment, metrics), metrics);
    }

    @Test
    void fairClaimTakesOneDueDeliveryPerTenantAndCreatesDurableCommands() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        for (int i = 0; i < 5; i++) insertDelivery(tenantA, UUID.randomUUID(), UUID.randomUUID());
        insertDelivery(tenantB, UUID.randomUUID(), UUID.randomUUID());

        scheduler.claimDue();

        assertThat(count("select count(*) from deliveries where tenant_id = ? and state = 'IN_FLIGHT'", tenantA))
                .isEqualTo(1);
        assertThat(count("select count(*) from deliveries where tenant_id = ? and state = 'IN_FLIGHT'", tenantB))
                .isEqualTo(1);
        assertThat(count("select count(*) from attempt_outbox", null)).isEqualTo(2);
    }

    @Test
    void endpointConcurrencyAndLogicalDeliveryUniquenessAreDatabaseEnforced() {
        UUID tenant = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        insertDelivery(tenant, endpoint, subscription, event);
        insertDelivery(tenant, endpoint, UUID.randomUUID(), UUID.randomUUID());

        scheduler.claimDue();

        assertThat(count("select count(*) from deliveries where endpoint_id = ? and state = 'IN_FLIGHT'", endpoint))
                .isEqualTo(1);
        assertThatThrownBy(() -> insertDelivery(tenant, UUID.randomUUID(), subscription, event))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void expiredWorkerLeaseIsRecoveredWithoutLosingAttemptEvidence() {
        UUID id = insertDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        scheduler.claimDue();
        jdbc.update("update deliveries set lease_expires_at = now() - interval '1 second' where id = ?", id);

        scheduler.recoverExpiredLeases();

        assertThat(jdbc.queryForObject("select state from deliveries where id = ?", String.class, id))
                .isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("select outcome from delivery_attempts where delivery_id = ?", String.class, id))
                .isEqualTo("LEASE_EXPIRED");
    }

    @Test
    void duplicateAcceptedEventCreatesOneLogicalDelivery() {
        UUID tenant = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        EndpointSnapshot endpoint = new EndpointSnapshot(endpointId, tenant, "https://example.com/hook",
                new DeliveryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5), 1, true),
                new EncryptedSecret("test", "ciphertext"), null, null);
        ControlPlaneClient control = (requestedTenant, type) -> List.of(new SubscriptionMatch(subscriptionId, endpoint, "{}"));
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var service = new DispatchService(jdbc, transactions, control, new PayloadFilter(new ObjectMapper()),
                new RetryPlanner(new java.util.Random(7)), new SimpleMeterRegistry());
        EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "test.event", 1, tenant, "source", Instant.now(),
                UUID.randomUUID(), null, null, "application/json", "{}".getBytes());

        service.fanOut(event);
        service.fanOut(event);

        assertThat(count("select count(*) from event_inbox", null)).isEqualTo(1);
        assertThat(count("select count(*) from deliveries", null)).isEqualTo(1);
    }

    private UUID insertDelivery(UUID tenant, UUID endpoint, UUID subscription) {
        return insertDelivery(tenant, endpoint, subscription, UUID.randomUUID());
    }

    private UUID insertDelivery(UUID tenant, UUID endpoint, UUID subscription, UUID event) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into deliveries(id, tenant_id, event_id, subscription_id, endpoint_id, endpoint_url,
                  event_type, schema_version, source_id, occurred_at, correlation_id, content_type, payload,
                  current_key_id, current_ciphertext, max_attempts, initial_delay_ms, max_delay_ms,
                  max_retry_after_ms, endpoint_concurrency, preserve_order, state, generation, attempt_count,
                  next_attempt_at, replay_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'https://example.com/hook', 'test.event', 1, 'source', ?, ?,
                  'application/json', '{}', 'local-v1', 'ciphertext', 3, 100, 1000, 1000, 1, true,
                  'SCHEDULED', 0, 0, ?, 0, ?, ?)
                """, id, tenant, event, subscription, endpoint, now, UUID.randomUUID(), "{}".getBytes(), now, now, now);
        return id;
    }

    private long count(String sql, UUID value) {
        Long count = value == null ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, value);
        return count == null ? 0 : count;
    }
}
