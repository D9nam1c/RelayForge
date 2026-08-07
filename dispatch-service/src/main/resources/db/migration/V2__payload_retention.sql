alter table deliveries add column payload_purged_at timestamptz;
alter table deliveries alter column current_key_id drop not null;
alter table deliveries alter column current_ciphertext drop not null;
