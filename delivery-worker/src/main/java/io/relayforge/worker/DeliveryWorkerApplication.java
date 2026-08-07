package io.relayforge.worker;

import io.relayforge.security.LocalAesGcmSecretCipher;
import io.relayforge.security.OutboundTargetPolicy;
import io.relayforge.security.SecretCipher;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DeliveryWorkerApplication {
    public static void main(String[] args) { SpringApplication.run(DeliveryWorkerApplication.class, args); }

    @Bean
    SecretCipher secretCipher(@Value("${relayforge.secrets.key-id:local-v1}") String keyId,
                              @Value("${relayforge.secrets.master-key}") String key) {
        return new LocalAesGcmSecretCipher(keyId, key);
    }

    @Bean
    OutboundTargetPolicy outboundTargetPolicy(
            @Value("${relayforge.outbound.allowed-ports:443}") String ports,
            @Value("${relayforge.features.development-sink-exception:false}") boolean allowSink,
            @Value("${relayforge.outbound.development-sink-host:webhook-sink}") String sinkHost) {
        Set<Integer> allowed = Arrays.stream(ports.split(",")).map(String::trim).map(Integer::parseInt)
                .collect(Collectors.toUnmodifiableSet());
        return new OutboundTargetPolicy(allowed, allowSink, sinkHost);
    }

    @Bean(destroyMethod = "close")
    HttpClient webhookHttpClient(@Value("${relayforge.outbound.connect-timeout:PT3S}") Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    }
}
