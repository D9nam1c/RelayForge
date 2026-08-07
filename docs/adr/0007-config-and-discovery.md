# ADR 0007: Local Config Server and Eureka

Status: Accepted — 2026-08-07

The demo uses Spring Cloud Config native files and Eureka because they make centralized settings and discovery visible on a laptop. Only validated, explicitly refreshable safe settings may use Spring Cloud Bus. Secrets and database credentials are not refreshable config. Kubernetes deployments should replace Eureka with Services/DNS and move configuration to versioned ConfigMaps plus a secrets manager; a Git-backed Config Server is a valid non-Kubernetes evolution.
