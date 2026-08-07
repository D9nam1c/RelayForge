# ADR 0005: Transactional outbox and consumer inbox

Status: Accepted — 2026-08-07

Every database mutation that must emit Kafka data inserts an outbox row in the same transaction. Pollers lock unpublished rows with `SKIP LOCKED`, publish, and mark them sent. A crash can republish, so consumers insert message IDs into a unique inbox table in the same transaction as domain effects. This closes lost-message and duplicate-domain-mutation gaps without claiming exactly-once transport.
