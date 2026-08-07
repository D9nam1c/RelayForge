# ADR 0009: Consumer inbox deduplication

Status: Accepted — 2026-08-07

Accepted-event IDs and attempt-result IDs are inserted into unique inbox tables in the same transaction as their effects. Worker command rows use a unique command ID plus a recoverable processing lease. A Kafka record can therefore repeat without creating another logical delivery or corrupting counters, while a crash after customer success can still repeat the external HTTP effect. Inbox rows are durable evidence, not a claim of exactly-once Kafka.
