# RelayForge portfolio notes

## 30-second explanation

RelayForge is a multi-tenant webhook reliability layer. Producers durably accept an event once; transactional outboxes and Kafka carry it to a fair PostgreSQL scheduler; workers deliver exact signed bytes with endpoint concurrency and bounded retries; inboxes, leases, dead letters, replay generations, and attempt history make crashes and duplicates explicit rather than magical. It promises at-least-once delivery and documents where duplicates remain possible.

## Five-minute architecture walkthrough

1. Gateway validates Keycloak JWTs, strips tenant spoofing, adds correlation, caps/rate-limits, and routes the only public APIs.
2. Ingest derives tenant from the token and commits event, tenant-scoped idempotency hash, and outbox. The response does not wait on any receiver.
3. Dispatch deduplicates Kafka, calls the narrow subscription snapshot API, filters/fans out, and durably ranks due work per tenant. A claim increments attempts, records a short lease, and creates a command outbox row.
4. Workers deduplicate/lease commands, take a Redis endpoint slot, validate the target, decrypt the endpoint-bound key, sign IDs/timestamp/raw bytes, and classify a bounded response. Result publication also uses an outbox.
5. Dispatch records the sanitized timeline and applies one state transition. Crashed leases are evidence-bearing ambiguous failures. Replay retains IDs/history and increments a generation.
6. Prometheus/Grafana show flow, queue/outbox pressure, outcomes, retries, and recovery; Tempo receives trace data; runbooks describe safe intervention.

## Difficult interview questions

**Why not exactly once?** The receiver database and RelayForge database cannot commit atomically. A crash after the receiver commits and before result recording creates an ambiguous duplicate. Signed IDs plus receiver dedupe are the honest solution.

**Why PostgreSQL scheduling instead of Kafka delayed retries?** PostgreSQL makes due time, state, lease, fairness, replay, and query history transactional and visible. Kafka transports commands; it is not asked to be a mutable timer index.

**Does an outbox make Kafka exactly once?** No. Publish-then-mark can republish after a crash. Consumer inbox constraints make domain mutations idempotent.

**How is noisy-neighbor isolation achieved?** Gateway token buckets, ranked per-tenant claim batches, tenant and endpoint in-flight limits, Redis endpoint slots, and bounded circuit groups. Shared infrastructure can still saturate; production needs quotas and capacity isolation.

**Is SSRF solved?** Representative private/reserved targets and redirects are blocked with two DNS checks, but Java `HttpClient` does not pin the approved address. An egress proxy/firewall is required to close the race.

**Why snapshot endpoint configuration?** It makes an attempt reproducible and keeps dispatch independent during execution. The tradeoff is that already queued work does not receive a newly rotated key; this is documented future work.

## Resume bullet options

- Built a Java 25/Spring Cloud multi-tenant webhook platform with transactional outbox/inbox, Kafka fan-out, PostgreSQL `SKIP LOCKED` scheduling, lease recovery, signed at-least-once delivery, bounded retry/dead-letter, and replayable attempt history.
- Engineered tenant isolation and noisy-neighbor controls across OIDC claims, Redis gateway quotas, fair per-tenant dispatch batches, endpoint concurrency, SSRF validation, encrypted rotating secrets, and negative PostgreSQL integration tests.
- Delivered an operable webhook reliability stack with Flyway-owned service databases, deterministic failure demos, Prometheus/Grafana/Tempo observability, alert/runbook coverage, Maven/CI quality gates, and explicit failure/retention guarantees.
