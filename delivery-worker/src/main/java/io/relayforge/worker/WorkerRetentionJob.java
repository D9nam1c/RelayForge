package io.relayforge.worker;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class WorkerRetentionJob {
    private final JdbcTemplate jdbc;
    private final Duration retention;

    WorkerRetentionJob(JdbcTemplate jdbc, @Value("${relayforge.worker.command-retention:P7D}") Duration retention) {
        this.jdbc = jdbc; this.retention = retention;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${relayforge.retention.poll-ms:3600000}")
    void deletePublishedCommands() {
        jdbc.update("""
                delete from result_outbox where published_at is not null
                  and completed_at < now() - (? * interval '1 millisecond')
                """, retention.toMillis());
        jdbc.update("""
                delete from worker_commands where status = 'COMPLETED' and completed_at < now() - (? * interval '1 millisecond')
                  and not exists (select 1 from result_outbox r where r.command_id = worker_commands.command_id)
                """, retention.toMillis());
    }
}
