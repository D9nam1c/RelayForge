package io.relayforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundTargetPolicyTest {
    @Test
    void acceptsPublicHttpsAndCanonicalizesHost() throws Exception {
        OutboundTargetPolicy policy = new OutboundTargetPolicy(Set.of(443), false, "webhook-sink",
                host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        assertThat(policy.validate("HTTPS://EXAMPLE.COM/hook").canonicalUri().toString())
                .isEqualTo("https://example.com/hook");
    }

    @Test
    void rejectsRepresentativeSsrfTargets() throws Exception {
        String[] blocked = {"127.0.0.1", "10.0.0.1", "169.254.169.254", "192.168.1.1", "::1", "fc00::1"};
        for (String address : blocked) {
            OutboundTargetPolicy policy = new OutboundTargetPolicy(Set.of(443), false, "webhook-sink",
                    host -> new InetAddress[] {InetAddress.getByName(address)});
            assertThatThrownBy(() -> policy.validate("https://attacker.example/hook"))
                    .as(address).isInstanceOf(OutboundTargetPolicy.BlockedTargetException.class);
        }
    }

    @Test
    void developmentExceptionIsExactAndExplicit() throws Exception {
        OutboundTargetPolicy dev = new OutboundTargetPolicy(Set.of(443), true, "webhook-sink",
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
        assertThat(dev.validate("http://webhook-sink:8090/webhooks/one").canonicalUri().getHost())
                .isEqualTo("webhook-sink");
        assertThatThrownBy(() -> dev.validate("http://webhook-sink.example:8090/webhooks/one"))
                .isInstanceOf(OutboundTargetPolicy.BlockedTargetException.class);
    }

    @Test
    void acceptsOrdinaryGlobalIpv6ButRejectsDocumentationRange() throws Exception {
        OutboundTargetPolicy publicPolicy = new OutboundTargetPolicy(Set.of(443), false, "webhook-sink",
                host -> new InetAddress[] {InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")});
        assertThat(publicPolicy.validate("https://example.com/hook").resolvedAddresses()).hasSize(1);

        OutboundTargetPolicy docsRange = new OutboundTargetPolicy(Set.of(443), false, "webhook-sink",
                host -> new InetAddress[] {InetAddress.getByName("2001:db8::1")});
        assertThatThrownBy(() -> docsRange.validate("https://example.com/hook"))
                .isInstanceOf(OutboundTargetPolicy.BlockedTargetException.class);
    }
}
