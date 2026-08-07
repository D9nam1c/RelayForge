package io.relayforge.worker;

import io.relayforge.contracts.AttemptCommand;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class WorkerConfiguration {
    @Bean
    Consumer<AttemptCommand> attemptCommands(WorkerService service) {
        return service::handle;
    }
}
