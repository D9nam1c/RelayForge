# RelayForge progress

Last updated: 2026-08-07

## Completed

- Built the Java 25 / Spring Boot 4.1.0 / Spring Cloud 2025.1.2 multi-module platform: Config Server, Eureka, API Gateway, control plane, ingest, dispatch, delivery worker, demo sink, and shared contract/security modules.
- Implemented tenant-scoped OAuth authorization, endpoint/subscription management, one-time AES-GCM-protected signing secrets and rotation, strict outbound target policy, and append-only audit evidence.
- Implemented durable accept/outbox, Kafka inbox/outbox boundaries, PostgreSQL fair scheduling and leases, bounded full-jitter retry, `Retry-After`, dead-letter, cancel, replay generations, payload retention, and a sanitized attempt ledger.
- Added Redis rate limits and endpoint slots, bounded circuit groups, HMAC verification examples, structured logs, metrics, tracing configuration, Prometheus alerts, and a Grafana dashboard.
- Added pinned Docker Compose infrastructure, generated local credentials, idempotent Keycloak seeding, deterministic success/retry/rate-limit/dead-letter/replay/rotation demos, and a load driver.
- Added unit, Spring Cloud Contract, and Testcontainers integration suites; CI, CodeQL, dependency review, container builds/scanning; ADRs, runbooks, an API collection, and portfolio documentation.

## Exact verification results

- `java -version`: passed with Java `25.0.1` LTS.
- `MAVEN_USER_HOME="$PWD/.cache/maven-home" ./mvnw clean verify`: **BUILD SUCCESS** on all 11 reactor modules in 17.578 seconds at `2026-08-07T14:32:16-04:00`.
- Surefire: 24 tests passed, zero failures/errors/skips. This includes the generated Spring Cloud Contract verifier test.
- Failsafe: seven PostgreSQL integration-test cases were discovered, zero failed, and all seven were skipped because Testcontainers found neither Docker Desktop nor `/var/run/docker.sock` on this host.
- `bash -n scripts/*.sh`: passed.
- `docker-compose.yml` and all repository YAML files: parsed successfully with Ruby's YAML parser.
- Grafana dashboard JSON: parsed successfully with Python's standard JSON tooling.
- `docker --version`: unavailable (`docker: command not found`), so Compose runtime validation, end-to-end demos, image execution, screenshots, and throughput measurements were not performed.

## Resume point on a Docker-enabled host

1. Run `make up`, wait for the health-gated stack and Keycloak seed to complete, then run `make demo`.
2. Run each focused demo target and `make load-test`; capture actual API timelines and Grafana/Tempo screenshots.
3. Run `./mvnw clean verify` with Docker so all seven PostgreSQL Testcontainers cases execute instead of skip.
4. Exercise Kafka restart/poison records and add Toxiproxy fault tests before treating those acceptance gaps as closed.

## Known limitations

- The SSRF policy cannot pin Java `HttpClient` to a prevalidated DNS answer; production needs an egress proxy/firewall to close the remaining resolution-to-connect race.
- Already-queued delivery snapshots do not refresh after key rotation; the overlap workflow applies to new fan-out and receiver verification.
- This is a production-minded local reference, not a production deployment: managed multi-AZ data services, KMS/Vault, TLS/mTLS, Kafka ACL/retention design, disaster recovery, multi-region strategy, and dedicated-tenant capacity remain production evolution work.
