package io.relayforge.worker;

import io.relayforge.contracts.AttemptCommand;
import io.relayforge.contracts.AttemptOutcome;
import io.relayforge.contracts.AttemptResult;
import io.relayforge.security.OutboundTargetPolicy;
import io.relayforge.security.SecretCipher;
import io.relayforge.security.WebhookSigner;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class WebhookHttpExecutor {
    private final HttpClient client;
    private final SecretCipher cipher;
    private final OutboundTargetPolicy targets;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final Clock clock;

    WebhookHttpExecutor(HttpClient client, SecretCipher cipher, OutboundTargetPolicy targets,
                        @Value("${relayforge.outbound.request-timeout:PT10S}") Duration timeout,
                        @Value("${relayforge.outbound.max-response-bytes:65536}") int maxResponseBytes) {
        this(client, cipher, targets, timeout, maxResponseBytes, Clock.systemUTC());
    }

    WebhookHttpExecutor(HttpClient client, SecretCipher cipher, OutboundTargetPolicy targets, Duration timeout,
                        int maxResponseBytes, Clock clock) {
        this.client = client; this.cipher = cipher; this.targets = targets; this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes; this.clock = clock;
    }

    AttemptResult execute(AttemptCommand command) {
        Instant started = clock.instant();
        Integer status = null;
        long responseBytes = 0;
        try {
            var target = targets.validate(command.endpoint().url());
            targets.revalidate(target);
            byte[] secret = cipher.decrypt(command.endpoint().currentSecret(), command.endpoint().endpointId().toString());
            Instant timestamp = clock.instant();
            byte[] payload = command.event().payload();
            String signature = WebhookSigner.sign(secret, command.event().messageId(), timestamp, payload);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target.canonicalUri()).timeout(timeout)
                    .header("Content-Type", command.event().payloadContentType())
                    .header("User-Agent", "RelayForge/0.1")
                    .header("Webhook-Id", command.event().messageId().toString())
                    .header("Webhook-Delivery-Id", command.deliveryId().toString())
                    .header("Webhook-Timestamp", Long.toString(timestamp.getEpochSecond()))
                    .header("Webhook-Attempt", Integer.toString(command.attemptNumber()))
                    .header("Webhook-Event-Type", command.event().type())
                    .header("Webhook-Signature", signature)
                    .header("X-Correlation-Id", command.event().correlationId().toString());
            if (command.event().traceparent() != null && !command.event().traceparent().isBlank()) {
                requestBuilder.header("traceparent", command.event().traceparent());
            }
            HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            responseBytes = drainBounded(response.body(), maxResponseBytes);
            if (responseBytes > maxResponseBytes) {
                return result(command, AttemptOutcome.RESPONSE_TOO_LARGE, status, started, null, responseBytes,
                        "response exceeded configured byte limit");
            }
            Instant completed = clock.instant();
            if (status >= 200 && status < 300) return result(command, AttemptOutcome.SUCCEEDED, status, started, null,
                    responseBytes, "accepted");
            if (status == 429) return result(command, AttemptOutcome.RATE_LIMITED, status, started,
                    RetryAfterParser.parse(response.headers().firstValue("Retry-After").orElse(null), completed),
                    responseBytes, "receiver rate limited delivery");
            if (status == 408 || status == 425 || status >= 500) return result(command, AttemptOutcome.RETRYABLE_HTTP,
                    status, started, null, responseBytes, "retryable HTTP response");
            if (status >= 300 && status < 400) return result(command, AttemptOutcome.PERMANENT_HTTP, status, started,
                    null, responseBytes, "redirect rejected");
            return result(command, AttemptOutcome.PERMANENT_HTTP, status, started, null, responseBytes,
                    "permanent HTTP response");
        } catch (OutboundTargetPolicy.BlockedTargetException blocked) {
            return result(command, AttemptOutcome.BLOCKED_TARGET, status, started, null, responseBytes,
                    blocked.getMessage());
        } catch (HttpTimeoutException timeoutException) {
            return result(command, AttemptOutcome.TIMEOUT, status, started, null, responseBytes, "request timed out");
        } catch (ConnectException connection) {
            return result(command, AttemptOutcome.CONNECTION_FAILURE, status, started, null, responseBytes,
                    "connection failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(command, AttemptOutcome.INTERNAL_FAILURE, status, started, null, responseBytes,
                    "worker interrupted");
        } catch (IOException io) {
            return result(command, AttemptOutcome.CONNECTION_FAILURE, status, started, null, responseBytes,
                    "network I/O failed");
        } catch (RuntimeException failure) {
            return result(command, AttemptOutcome.INTERNAL_FAILURE, status, started, null, responseBytes,
                    "worker execution failed: " + failure.getClass().getSimpleName());
        }
    }

    private long drainBounded(InputStream body, int maximum) throws IOException {
        try (body) {
            byte[] buffer = new byte[Math.min(8192, maximum + 1)];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > maximum) return total;
            }
            return total;
        }
    }

    private AttemptResult result(AttemptCommand command, AttemptOutcome outcome, Integer status, Instant started,
                                 Instant retryAfter, long responseBytes, String detail) {
        Instant completed = clock.instant();
        return new AttemptResult(UUID.randomUUID(), command.commandId(), command.deliveryId(),
                command.event().tenantId(), command.executionGeneration(), command.attemptNumber(), outcome, status,
                Duration.between(started, completed), completed, retryAfter, responseBytes, detail);
    }
}
