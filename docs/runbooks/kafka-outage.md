# Runbook: Kafka outage

1. Ingest may continue to return `202` while its PostgreSQL outbox has capacity; monitor outbox bytes/age and stop acceptance before exhausting storage.
2. Dispatch scheduling pauses when command outbox pressure crosses its threshold. Workers can finish already received attempts and persist results locally.
3. Restore broker quorum/network/ACLs. Do not purge outboxes or reset consumer offsets forward.
4. Expect duplicate records after reconnect; validate inbox duplicate metrics and delivery counters, then drain gradually to protect receivers.
5. Confirm all three outboxes reach a stable baseline and named DLQs contain no unexpected poison records.
