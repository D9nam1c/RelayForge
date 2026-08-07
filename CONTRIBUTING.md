# Contributing

RelayForge favors small vertical changes that leave the full reactor runnable.

1. Use Java 25. Run `./mvnw verify` before opening a change.
2. Keep service data ownership strict. Shared modules may contain wire contracts, cryptography, or observability conventions—not entities or repositories.
3. Add a Flyway migration; never edit an applied migration. Include a real PostgreSQL test for PostgreSQL-specific concurrency/constraint behavior.
4. Any retry must have one explicit owner. Do not add binder, Feign, HTTP-client, or application retries without proving they cannot multiply.
5. Tenant identity must originate in a verified token and every persistence/cache lookup must include it. Add a negative cross-tenant test.
6. Never log or commit payloads, bearer tokens, `.env`, signing keys, generated credentials, database volumes, or build output.
7. Update an ADR for consequential guarantees or tradeoffs; update `PROGRESS.md` with exact verification.

Commit messages should be imperative and scoped. Security reports should not be filed publicly; use the repository security advisory flow.
