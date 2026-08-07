package io.relayforge.gateway;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
class GatewayConfiguration {
    static final String CORRELATION_ID = "X-Correlation-Id";

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/v1/events/**").hasAuthority("SCOPE_events:write")
                        .pathMatchers("/v1/**").authenticated()
                        .anyExchange().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> writeProblem(exchange, HttpStatus.UNAUTHORIZED,
                                "Authentication required", "A valid access token is required"))
                        .accessDeniedHandler((exchange, exception) -> writeProblem(exchange, HttpStatus.FORBIDDEN,
                                "Forbidden", "The token does not grant this operation")))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    GlobalFilter correlationFilter() {
        return (exchange, chain) -> {
            String incoming = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID);
            String correlationId = validUuid(incoming) ? incoming : UUID.randomUUID().toString();
            var request = exchange.getRequest().mutate()
                    .header(CORRELATION_ID, correlationId)
                    .headers(headers -> {
                        headers.remove(HttpHeaders.COOKIE);
                        headers.remove("X-Tenant-Id");
                    })
                    .build();
            exchange.getResponse().getHeaders().set(CORRELATION_ID, correlationId);
            return chain.filter(exchange.mutate().request(request).build());
        };
    }

    @Bean
    KeyResolver tenantKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication().getPrincipal())
                .filter(Jwt.class::isInstance)
                .cast(Jwt.class)
                .map(jwt -> "tenant:" + jwt.getClaimAsString("tenant_id"))
                .filter(key -> !key.endsWith("null"))
                .defaultIfEmpty("unauthenticated");
    }

    private static reactor.core.publisher.Mono<Void> writeProblem(
            org.springframework.web.server.ServerWebExchange exchange, HttpStatus status, String title, String detail) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"type\":\"https://relayforge.local/problems/" + status.value()
                + "\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}";
        var buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(reactor.core.publisher.Mono.just(buffer));
    }

    private static boolean validUuid(String value) {
        if (value == null) return false;
        try { UUID.fromString(value); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
