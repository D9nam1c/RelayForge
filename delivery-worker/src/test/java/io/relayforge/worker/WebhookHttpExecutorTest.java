package io.relayforge.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.relayforge.contracts.AttemptCommand;
import io.relayforge.contracts.AttemptOutcome;
import io.relayforge.contracts.DeliveryPolicy;
import io.relayforge.contracts.EndpointSnapshot;
import io.relayforge.contracts.EventEnvelope;
import io.relayforge.security.LocalAesGcmSecretCipher;
import io.relayforge.security.OutboundTargetPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class WebhookHttpExecutorTest {
    private static final byte[] SECRET = "worker-test-secret".getBytes(StandardCharsets.UTF_8);
    private static final LocalAesGcmSecretCipher CIPHER = new LocalAesGcmSecretCipher("test",
            Base64.getEncoder().encodeToString(new byte[32]));
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void classifiesSuccessRateLimitRetryablePermanentAndRedirect() throws Exception {
        var success = execute(response(204, new byte[0], null), 16);
        assertThat(success.outcome()).as(success.detail()).isEqualTo(AttemptOutcome.SUCCEEDED);
        var rateLimited = execute(response(429, new byte[0], "3"), 16);
        assertThat(rateLimited.outcome()).isEqualTo(AttemptOutcome.RATE_LIMITED);
        assertThat(rateLimited.retryAfter()).isEqualTo(NOW.plusSeconds(3));
        assertThat(execute(response(503, "no".getBytes(), null), 16).outcome())
                .isEqualTo(AttemptOutcome.RETRYABLE_HTTP);
        assertThat(execute(response(422, "bad".getBytes(), null), 16).outcome())
                .isEqualTo(AttemptOutcome.PERMANENT_HTTP);
        assertThat(execute(response(302, new byte[0], null), 16).detail()).isEqualTo("redirect rejected");
    }

    @Test
    void boundsResponsesAndTimeouts() throws Exception {
        var oversized = execute(response(200, new byte[64], null), 8);
        assertThat(oversized.outcome()).as(oversized.detail())
                .isEqualTo(AttemptOutcome.RESPONSE_TOO_LARGE);
        HttpClient timeout = new StubClient(new HttpTimeoutException("slow"));
        assertThat(execute(timeout, 8).outcome()).isEqualTo(AttemptOutcome.TIMEOUT);
    }

    @Test
    void refusedConnectionIsRetryable() throws Exception {
        HttpClient refused = new StubClient(new ConnectException("refused"));
        assertThat(execute(refused, 8).outcome()).isEqualTo(AttemptOutcome.CONNECTION_FAILURE);
    }

    private static io.relayforge.contracts.AttemptResult execute(HttpResponse<java.io.InputStream> response,
                                                                 int responseLimit) throws Exception {
        return execute(new StubClient(response), responseLimit);
    }

    private static io.relayforge.contracts.AttemptResult execute(HttpClient client, int responseLimit) {
        var targetPolicy = new OutboundTargetPolicy(Set.of(443), true, "localhost");
        var executor = new WebhookHttpExecutor(client, CIPHER, targetPolicy, Duration.ofMillis(50), responseLimit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID tenant = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        var event = new EventEnvelope(UUID.randomUUID(), "test.event", 1, tenant, "source", NOW,
                UUID.randomUUID(), null, null, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        var endpoint = new EndpointSnapshot(endpointId, tenant, "http://localhost:8090/hook",
                new DeliveryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(3), 1, true),
                CIPHER.encrypt(SECRET, endpointId.toString()), null, null);
        return executor.execute(new AttemptCommand(UUID.randomUUID(), UUID.randomUUID(), 0, 1,
                NOW.plusSeconds(5), event, endpoint));
    }

    private static HttpResponse<java.io.InputStream> response(int status, byte[] body, String retryAfter) {
        HttpHeaders headers = HttpHeaders.of(retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter)),
                (name, value) -> true);
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<java.io.InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return headers; }
            @Override public java.io.InputStream body() { return new ByteArrayInputStream(body); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("http://localhost:8090/hook"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static final class StubClient extends HttpClient {
        private final HttpResponse<java.io.InputStream> response;
        private final IOException failure;

        private StubClient(HttpResponse<java.io.InputStream> response) { this.response = response; this.failure = null; }
        private StubClient(IOException failure) { this.response = null; this.failure = failure; }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            if (failure != null) throw failure;
            return (HttpResponse<T>) response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try { return CompletableFuture.completedFuture(send(request, handler)); }
            catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                 HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }
}
