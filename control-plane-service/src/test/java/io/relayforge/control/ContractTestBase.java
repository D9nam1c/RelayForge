package io.relayforge.control;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

public abstract class ContractTestBase {
    @BeforeEach
    void contractSetup() {
        EndpointService service = new EndpointService(null, null, null, Duration.ofMinutes(15)) {
            @Override
            List<io.relayforge.contracts.SubscriptionMatch> matching(java.util.UUID tenantId, String eventType) {
                return List.of();
            }
        };
        RestAssuredMockMvc.standaloneSetup(new EndpointController(service, null));
    }
}
