# Runbook: database outage

1. Identify the owning database. Ingest DB loss means durable acceptance must fail; dispatch DB loss stops fan-out/scheduling/results; worker DB loss stops safe result persistence.
2. Remove a failed instance from readiness and stop new claims/traffic. Do not switch to Redis or Kafka as an improvised delivery ledger.
3. Restore through the managed service or tested PITR procedure. Validate Flyway version and referential/unique constraints before traffic.
4. In an ambiguous failover, assume Kafka and HTTP duplicates. Let inbox/outbox logic reconcile; never reset counters manually.
5. Verify one idempotency request, one fan-out, one attempt result, oldest queue age, and tenant isolation before full traffic.
