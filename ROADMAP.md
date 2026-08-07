# Roadmap

## Implemented

- Durable idempotent ingest and transactional outbox.
- Tenant subscription fan-out, fair durable schedule, leases, retry/dead-letter/replay.
- Signed bounded delivery, rotation overlap, SSRF controls, audit, retention jobs.
- Local Config/Eureka/Gateway/Keycloak/Kafka/PostgreSQL/Redis/Prometheus/Tempo/Grafana stack.
- Unit and PostgreSQL integration tests plus deterministic demo scripts.

## Next (not implemented)

- Close the DNS validation/connect race through a pinned egress proxy.
- Kafka duplicate/restart/poison and Toxiproxy end-to-end suites; Spring Cloud Contract producer/consumer verification.
- Refresh queued delivery key snapshots after endpoint rotation without violating historical evidence.
- Weighted paid-plan quotas, endpoint pause/resume, subscription change events, and tenant erasure workflow.
- Schema registry, CDC outbox, KMS/Vault implementation, Kubernetes packaging, autoscaling policies, and DR exercises.
- Thin operator dashboard after backend acceptance is verified under Docker Compose.
