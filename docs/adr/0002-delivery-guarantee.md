# ADR 0002: At-least-once webhook delivery

Status: Accepted — 2026-08-07

RelayForge acknowledges a producer only after committing the event and outbox row in PostgreSQL. Kafka and webhook execution are at least once. A crash after the receiver commits but before RelayForge records the response can cause a duplicate. Receivers must verify the signature and timestamp, then persist `Webhook-Id` before applying side effects.

Exactly-once delivery is not claimed because RelayForge cannot atomically commit a customer system and its own database.
