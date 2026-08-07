# Runbook: dead-letter spike

1. Group by bounded outcome/status and event type using the attempt ledger; never export payloads or full URLs.
2. Separate permanent 4xx/SSRF blocks from attempts exhausted after 429, 5xx, timeout, or lease expiry. Check recent endpoint/policy/secret audit changes.
3. Contact the tenant for customer endpoint failures. Do not replay broadly until the receiver is fixed and deduplication readiness is confirmed.
4. Use a small canary replay, verify the new execution generation succeeds, then replay a bounded batch. Preserve the original event and all attempt evidence.
5. If signatures fail after rotation, verify both keys and the overlap time; do not extend an exposed key after suspected compromise.
