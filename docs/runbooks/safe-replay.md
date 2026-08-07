# Runbook: safe replay

1. Confirm delivery is `DEAD_LETTERED`, payload has not been purged, the receiver fault is fixed, and the receiver persists `Webhook-Id` atomically.
2. Inspect every prior attempt, terminal reason, endpoint/key changes, and incident scope. Obtain tenant-operator authorization.
3. Replay one delivery. RelayForge keeps event/delivery IDs, increments the execution generation, resets the generation attempt count, and appends audit evidence.
4. Verify success at RelayForge and the receiver before a bounded batch. Stop on new 4xx, signature errors, queue-age regression, or saturation.
5. Never create a new producer event merely to replay; that changes the receiver dedupe key and can duplicate side effects.
