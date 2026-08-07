package io.relayforge.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryAfterParserTest {
    private final Instant now = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void acceptsDeltaSecondsAndHttpDate() {
        assertThat(RetryAfterParser.parse("12", now)).isEqualTo(now.plusSeconds(12));
        assertThat(RetryAfterParser.parse("Fri, 7 Aug 2026 12:01:00 GMT", now)).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void rejectsNegativePastAndMalformedValues() {
        assertThat(RetryAfterParser.parse("-1", now)).isNull();
        assertThat(RetryAfterParser.parse("Fri, 7 Aug 2020 12:01:00 GMT", now)).isNull();
        assertThat(RetryAfterParser.parse("tomorrow", now)).isNull();
    }
}
