# Runbook: signing-secret compromise

1. Restrict access and record an incident; never paste the key into tickets, chat, logs, or shell history.
2. Rotate the endpoint secret. If compromise is active, set overlap to the minimum operationally possible or disable the endpoint; normal zero-downtime overlap is unsafe for a stolen key.
3. Notify the receiver to install the new key, revoke the old key, tighten timestamp tolerance, and inspect duplicate/message-ID history.
4. Determine whether the local/production master key or worker decrypt identity was exposed. If so, rotate every affected data key through the KMS/Vault procedure, not one endpoint.
5. Review immutable endpoint/rotation/privileged-access audit rows and outbound anomaly data without exposing payloads.
