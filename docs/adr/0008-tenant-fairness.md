# ADR 0008: Bounded tenant fairness

Status: Accepted — 2026-08-07

Each due-work query ranks deliveries within a tenant and takes no more than the configured per-tenant batch before ordering by rank and due time. Tenant and endpoint in-flight counts gate admission; the gateway separately uses tenant token buckets. This is starvation resistance, not reserved capacity or billing-grade weighted fair queuing. Dedicated tiers can later supply weights or isolated worker pools without changing delivery semantics.
