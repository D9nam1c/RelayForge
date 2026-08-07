# Local load-test methodology

`COUNT=500 CONCURRENCY=16 make load-test` sends unique, small JSON events through the authenticated gateway and reports client-observed accepted requests per wall-clock second. It measures acceptance only; it does not imply endpoint delivery throughput. Before a run, create a `load.test` subscription if delivery throughput is being studied, start from known queue/outbox baselines, record payload size, partition count, container CPU/memory, Java/Docker versions, and host model, then wait for terminal states.

No observed result is published from the repository creation environment because Docker is not installed there. Publishing a fabricated or unit-test-derived throughput number would be misleading. The first Docker-enabled maintainer should capture:

```text
Date/commit:
Host CPU/RAM/OS:
Docker/Java versions:
COUNT / CONCURRENCY / payload bytes:
Acceptance rate and p50/p95/p99:
Accepted-to-first-attempt p50/p95/p99:
Terminal distribution and oldest queue age:
Postgres/Kafka/worker saturation notes:
```

Laptop results are local capacity observations, never universal product claims.
