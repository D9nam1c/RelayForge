package io.relayforge.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.mock.env.MockEnvironment;

class SafeSchedulingSettingsTest {
    @Test
    void invalidBusRefreshLeavesLastKnownGoodSettingsActive() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("relayforge.scheduling.batch-size", "100")
                .withProperty("relayforge.scheduling.per-tenant-batch", "10");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SafeSchedulingSettings settings = new SafeSchedulingSettings(environment, metrics);

        environment.setProperty("relayforge.scheduling.batch-size", "2");
        environment.setProperty("relayforge.scheduling.per-tenant-batch", "10");
        settings.refresh(new EnvironmentChangeEvent(Set.of("relayforge.scheduling.batch-size")));

        assertThat(settings.current().batchSize()).isEqualTo(100);
        assertThat(settings.current().perTenantBatch()).isEqualTo(10);
        assertThat(metrics.counter("relayforge.config.refresh", "outcome", "rejected").count()).isEqualTo(1);
    }

    @Test
    void validBusRefreshSwapsTheWholeSnapshot() {
        MockEnvironment environment = new MockEnvironment();
        SafeSchedulingSettings settings = new SafeSchedulingSettings(environment, new SimpleMeterRegistry());
        environment.setProperty("relayforge.scheduling.batch-size", "50");
        environment.setProperty("relayforge.scheduling.per-tenant-batch", "5");

        settings.refresh(new EnvironmentChangeEvent(Set.of("relayforge.scheduling.batch-size")));

        assertThat(settings.current().batchSize()).isEqualTo(50);
        assertThat(settings.current().perTenantBatch()).isEqualTo(5);
    }
}
