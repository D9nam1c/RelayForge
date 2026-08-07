package io.relayforge.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.relayforge.contracts.DeliveryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPlannerTest {
    private final Instant now = Instant.parse("2026-08-07T12:00:00Z");
    private final DeliveryPolicy policy = new DeliveryPolicy(6, Duration.ofSeconds(2), Duration.ofSeconds(30),
            Duration.ofSeconds(20), 1, true);

    @Test
    void usesFullJitterWithinExponentialCap() {
        RetryPlanner planner = new RetryPlanner(new FixedRandom(3_000));
        assertThat(planner.nextAttempt(policy, 3, now, null)).isEqualTo(now.plusSeconds(3));
    }

    @Test
    void honorsButBoundsRetryAfter() {
        RetryPlanner planner = new RetryPlanner(new FixedRandom(0));
        assertThat(planner.nextAttempt(policy, 1, now, now.plusSeconds(12))).isEqualTo(now.plusSeconds(12));
        assertThat(planner.nextAttempt(policy, 1, now, now.plusSeconds(90))).isEqualTo(now.plusSeconds(20));
    }

    private static final class FixedRandom implements RandomGenerator {
        private final long value;
        private FixedRandom(long value) { this.value = value; }
        @Override public long nextLong() { return value; }
        @Override public long nextLong(long bound) { return Math.min(value, bound - 1); }
    }
}
