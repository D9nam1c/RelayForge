package io.relayforge.dispatch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryStateMachineTest {
    @Test
    void permitsDocumentedRetryAndReplayCycle() {
        assertThatCode(() -> {
            DeliveryStateMachine.requireTransition(DeliveryState.SCHEDULED, DeliveryState.IN_FLIGHT);
            DeliveryStateMachine.requireTransition(DeliveryState.IN_FLIGHT, DeliveryState.RETRY_WAIT);
            DeliveryStateMachine.requireTransition(DeliveryState.RETRY_WAIT, DeliveryState.IN_FLIGHT);
            DeliveryStateMachine.requireTransition(DeliveryState.IN_FLIGHT, DeliveryState.DEAD_LETTERED);
            DeliveryStateMachine.requireTransition(DeliveryState.DEAD_LETTERED, DeliveryState.SCHEDULED);
        }).doesNotThrowAnyException();
    }

    @Test
    void terminalSuccessCannotBeRetried() {
        assertThatThrownBy(() -> DeliveryStateMachine.requireTransition(DeliveryState.SUCCEEDED,
                DeliveryState.RETRY_WAIT)).isInstanceOf(IllegalStateException.class);
    }
}
