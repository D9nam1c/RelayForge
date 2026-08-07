package io.relayforge.sink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class SinkRegistry {
    private final ConcurrentHashMap<String, SinkEndpoint> endpoints = new ConcurrentHashMap<>();
    private final Set<String> seenMessages = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<SinkAttempt> timeline = new ConcurrentLinkedDeque<>();
    private final Duration timestampTolerance;
    private final Clock clock;

    SinkRegistry(@Value("${relayforge.sink.timestamp-tolerance:PT5M}") Duration timestampTolerance) {
        this(timestampTolerance, Clock.systemUTC());
    }

    SinkRegistry(Duration timestampTolerance, Clock clock) {
        this.timestampTolerance = timestampTolerance; this.clock = clock;
    }

    void configure(String id, SinkEndpoint endpoint) { endpoints.put(id, endpoint); }
    SinkEndpoint endpoint(String id) { return endpoints.get(id); }
    boolean firstSeen(String endpointId, UUID messageId) { return seenMessages.add(endpointId + ":" + messageId); }
    Instant now() { return clock.instant(); }
    boolean timestampAllowed(Instant timestamp) { return Duration.between(timestamp, now()).abs().compareTo(timestampTolerance) <= 0; }
    void record(SinkAttempt attempt) {
        timeline.addLast(attempt);
        while (timeline.size() > 2_000) timeline.pollFirst();
    }
    List<SinkAttempt> timeline() {
        List<SinkAttempt> copy = new ArrayList<>(timeline);
        copy.sort(Comparator.comparing(SinkAttempt::receivedAt));
        return List.copyOf(copy);
    }

    enum Mode { SUCCESS, FLAKY, RATE_LIMIT, ALWAYS_500, TIMEOUT, DELAYED_SUCCESS, DUPLICATE_SENSITIVE }
    record SinkEndpoint(String currentSecret, String previousSecret, Instant previousValidUntil, Mode mode,
                        int failuresBeforeSuccess, long delayMillis, long retryAfterSeconds) {
        SinkEndpoint {
            if (currentSecret == null || currentSecret.isBlank()) throw new IllegalArgumentException("currentSecret is required");
            if ((previousSecret == null) != (previousValidUntil == null)) {
                throw new IllegalArgumentException("previousSecret and previousValidUntil must be supplied together");
            }
            if (mode == null) mode = Mode.SUCCESS;
            if (failuresBeforeSuccess < 0 || delayMillis < 0 || retryAfterSeconds < 0) {
                throw new IllegalArgumentException("mode parameters must not be negative");
            }
        }
    }
    record SinkAttempt(String endpointId, UUID messageId, int attempt, String eventType, int status,
                       Instant receivedAt) {}
}
