# Captured evidence

This directory intentionally does not contain fabricated UI screenshots. The creation host has no Docker runtime, so Grafana, Tempo, Swagger, the sink timeline, and the noisy-neighbor demo could not be launched and truthfully captured. `PROGRESS.md` records that verification gap. On the first Docker-enabled run, capture actual screens only after `make demo` and store sanitized images named `delivery-timeline.png`, `grafana-dashboard.png`, `tempo-trace.png`, `openapi.png`, and `noisy-neighbor.png` with the commit hash and commands in this file.
