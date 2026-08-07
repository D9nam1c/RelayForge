# ADR 0001: Service boundaries and data ownership

Status: Accepted — 2026-08-07

Control plane owns tenants, endpoints, subscriptions, encrypted signing keys, policies, quotas, and audit. Ingest owns immutable accepted events and producer idempotency. Dispatch owns delivery snapshots, attempts, scheduling, replay, cancellation, and its Kafka inbox/outbox. Workers own only their command inbox; they never become the durable delivery ledger.

Services may share versioned wire contracts, cryptographic helpers, and observability conventions. They do not share entities, repositories, tables, or migration files. The local PostgreSQL instance contains separate databases to make accidental cross-service queries fail.
