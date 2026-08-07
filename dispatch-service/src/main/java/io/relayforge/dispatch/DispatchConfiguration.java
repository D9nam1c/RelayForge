package io.relayforge.dispatch;

import feign.RequestInterceptor;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
class DispatchConfiguration {
    @Bean
    RequestInterceptor internalAuthentication(@Value("${relayforge.internal.token}") String token) {
        return template -> template.header("X-RelayForge-Internal-Token", token);
    }

    @Bean
    RetryPlanner retryPlanner() { return new RetryPlanner(RandomGenerator.getDefault()); }

    @Bean
    PayloadFilter payloadFilter(ObjectMapper mapper) { return new PayloadFilter(mapper); }

    @Bean
    Consumer<io.relayforge.contracts.EventEnvelope> acceptedEvents(DispatchService service) {
        return service::fanOut;
    }

    @Bean
    Consumer<io.relayforge.contracts.AttemptResult> attemptResults(DispatchService service) {
        return service::recordResult;
    }
}
