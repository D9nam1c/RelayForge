package io.relayforge.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Refreshes only after the complete candidate has passed cross-field validation. */
@Component
class SafeSchedulingSettings {
    record Values(int batchSize, int perTenantBatch, int tenantInFlight, int maxUnpublishedOutbox,
                  Duration leaseDuration) {
        Values {
            if (batchSize < 1 || batchSize > 1_000) throw new IllegalArgumentException("batchSize must be 1..1000");
            if (perTenantBatch < 1 || perTenantBatch > batchSize) {
                throw new IllegalArgumentException("perTenantBatch must be 1..batchSize");
            }
            if (tenantInFlight < 1 || tenantInFlight > 10_000) {
                throw new IllegalArgumentException("tenantInFlight must be 1..10000");
            }
            if (maxUnpublishedOutbox < batchSize) {
                throw new IllegalArgumentException("outbox pause threshold must be at least one batch");
            }
            if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                    || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("leaseDuration must be 5 seconds..5 minutes");
            }
        }
    }

    private static final Set<String> KEYS = Set.of(
            "relayforge.scheduling.batch-size", "relayforge.scheduling.per-tenant-batch",
            "relayforge.quotas.tenant-in-flight", "relayforge.scheduling.pause-outbox-backlog",
            "relayforge.scheduling.lease-duration");
    private final Environment environment;
    private final MeterRegistry metrics;
    private final AtomicReference<Values> current;

    SafeSchedulingSettings(Environment environment, MeterRegistry metrics) {
        this.environment = environment;
        this.metrics = metrics;
        this.current = new AtomicReference<>(read());
    }

    Values current() { return current.get(); }

    @EventListener
    void refresh(EnvironmentChangeEvent event) {
        if (event.getKeys().stream().noneMatch(KEYS::contains)) return;
        try {
            current.set(read());
            metrics.counter("relayforge.config.refresh", "outcome", "accepted").increment();
        } catch (RuntimeException invalid) {
            metrics.counter("relayforge.config.refresh", "outcome", "rejected").increment();
        }
    }

    private Values read() {
        return new Values(environment.getProperty("relayforge.scheduling.batch-size", Integer.class, 100),
                environment.getProperty("relayforge.scheduling.per-tenant-batch", Integer.class, 10),
                environment.getProperty("relayforge.quotas.tenant-in-flight", Integer.class, 100),
                environment.getProperty("relayforge.scheduling.pause-outbox-backlog", Integer.class, 10_000),
                environment.getProperty("relayforge.scheduling.lease-duration", Duration.class, Duration.ofSeconds(45)));
    }
}
