# Runbook: stuck outbox

Alert: `RelayForgeOutboxStall` or a nonzero unpublished gauge with zero publish rate.

1. Determine which owner is stuck: ingest accepted-event outbox, dispatch command outbox, or worker result outbox.
2. Check Kafka broker health, binder binding health, authentication/ACL errors, serialization errors, and DB locks. A poison row must not be marked published.
3. Restore Kafka/binder connectivity. Pollers are safe to restart: publish-before-mark can duplicate Kafka records, and inbox constraints are designed for that.
4. Never manually set `published_at` without independent proof the exact message exists in Kafka. If manual republish is required, use the existing message/command/result ID.
5. Verify backlog and oldest-row age decline, then inspect DLQ topics and duplicate counters.
