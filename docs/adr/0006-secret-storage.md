# ADR 0006: Signing secret storage and rotation

Status: Accepted — 2026-08-07

Endpoint secrets use 256-bit random values, are returned only at creation/rotation, and are encrypted with AES-256-GCM under a versioned key-provider interface. Rotation retains the prior key only until a bounded overlap expires. The local provider reads a development master key from an environment variable; production must use envelope encryption backed by a KMS or Vault and restrict decrypt permission to the delivery path.
