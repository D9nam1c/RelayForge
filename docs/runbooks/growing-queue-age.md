# Runbook: growing queue age

Alert: `RelayForgeQueueAgeHigh` or rising `relayforge_delivery_queue_oldest_seconds`.

1. Confirm whether acceptance continues and split due backlog from future retry wait. Check dispatch readiness, DB pool saturation, command outbox backlog, Kafka command lag, worker rate, endpoint saturation, and open circuits.
2. If command outbox is high, follow the stuck-outbox runbook. If workers are saturated but dependencies are healthy, scale workers only up to Kafka partitions and DB/Redis capacity.
3. Identify whether one tenant dominates using tenant-safe database administration queries; do not add tenant IDs to metrics. Lower its quota or pause its endpoints through an approved operational change.
4. Preserve due timestamps. Do not bulk rewrite `next_attempt_at` or delete queue rows. After recovery, verify oldest age falls monotonically and healthy-tenant synthetic delivery meets the SLO.
5. Capture incident interval, dependency saturation, accepted/attempt rates, and whether any lease expired.
