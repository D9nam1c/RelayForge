package io.relayforge.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PayloadFilterTest {
    private final PayloadFilter filter = new PayloadFilter(new ObjectMapper());

    @Test
    void matchesTopLevelExactValuesAndFailsClosed() {
        byte[] payload = "{\"region\":\"us\",\"priority\":2}".getBytes(StandardCharsets.UTF_8);
        assertThat(filter.matches("{\"region\":\"us\"}", payload)).isTrue();
        assertThat(filter.matches("{\"region\":\"eu\"}", payload)).isFalse();
        assertThat(filter.matches("not-json", payload)).isFalse();
    }
}
