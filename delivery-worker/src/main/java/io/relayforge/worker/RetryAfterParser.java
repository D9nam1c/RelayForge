package io.relayforge.worker;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class RetryAfterParser {
    private RetryAfterParser() {}

    static Instant parse(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) return null;
            return now.plusSeconds(seconds);
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                Instant parsed = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return parsed.isBefore(now) ? null : parsed;
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }
}
