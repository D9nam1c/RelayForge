# ADR 0004: Endpoint-scoped ordering

Status: Accepted — 2026-08-07

Attempt commands use endpoint ID as the Kafka partition key. Endpoints may request serial delivery, implemented by an endpoint concurrency limit of one. This preserves attempt command order for that endpoint under normal operation but does not suppress duplicates after ambiguous failures. There is no global tenant ordering because it would create avoidable head-of-line blocking.
