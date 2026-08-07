# ADR 0003: PostgreSQL scheduling, leases, and tenant fairness

Status: Accepted — 2026-08-07

Dispatch stores `next_attempt_at` durably and claims due work with `FOR UPDATE SKIP LOCKED`. A short lease makes crashed work recoverable. Each claim takes a bounded number per tenant and interleaves tenant ranks, preventing a large backlog from monopolizing a batch. Tenant token buckets and endpoint concurrency add admission control; Kafka remains transport, not the scheduler of record.
