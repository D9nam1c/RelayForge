create table event_inbox (
    message_id uuid primary key,
    tenant_id uuid not null,
    received_at timestamptz not null
);

create table deliveries (
    id uuid primary key,
    tenant_id uuid not null,
    event_id uuid not null,
    subscription_id uuid not null,
    endpoint_id uuid not null,
    endpoint_url varchar(2048) not null,
    event_type varchar(128) not null,
    schema_version integer not null,
    source_id varchar(200) not null,
    occurred_at timestamptz not null,
    correlation_id uuid not null,
    causation_id uuid,
    traceparent varchar(512),
    content_type varchar(100) not null,
    payload bytea not null,
    current_key_id varchar(100) not null,
    current_ciphertext text not null,
    previous_key_id varchar(100),
    previous_ciphertext text,
    previous_valid_until timestamptz,
    max_attempts integer not null,
    initial_delay_ms bigint not null,
    max_delay_ms bigint not null,
    max_retry_after_ms bigint not null,
    endpoint_concurrency integer not null,
    preserve_order boolean not null,
    state varchar(30) not null check (state in ('PENDING','SCHEDULED','IN_FLIGHT','SUCCEEDED','RETRY_WAIT','DEAD_LETTERED','CANCELLED')),
    generation integer not null default 0,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz,
    lease_command_id uuid,
    lease_expires_at timestamptz,
    terminal_reason varchar(240),
    replay_count integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (event_id, subscription_id),
    check ((state = 'IN_FLIGHT' and lease_command_id is not null and lease_expires_at is not null)
        or (state <> 'IN_FLIGHT' and lease_command_id is null and lease_expires_at is null))
);
create index deliveries_tenant_idx on deliveries(tenant_id, created_at desc);
create index deliveries_due_idx on deliveries(next_attempt_at, tenant_id) where state in ('SCHEDULED','RETRY_WAIT');
create index deliveries_endpoint_flight_idx on deliveries(endpoint_id) where state = 'IN_FLIGHT';

create table attempt_outbox (
    id uuid primary key,
    command_id uuid not null unique,
    delivery_id uuid not null references deliveries(id),
    generation integer not null,
    attempt_number integer not null,
    created_at timestamptz not null,
    published_at timestamptz
);
create index attempt_outbox_due_idx on attempt_outbox(created_at) where published_at is null;

create table result_inbox (
    result_id uuid primary key,
    received_at timestamptz not null
);

create table delivery_attempts (
    id uuid primary key,
    delivery_id uuid not null references deliveries(id),
    generation integer not null,
    attempt_number integer not null,
    outcome varchar(40) not null,
    http_status integer,
    latency_ms bigint not null,
    completed_at timestamptz not null,
    retry_after timestamptz,
    response_bytes bigint not null,
    detail varchar(160),
    late_result boolean not null,
    unique (delivery_id, generation, attempt_number)
);
create index attempts_timeline_idx on delivery_attempts(delivery_id, generation, attempt_number);

create table dispatch_audit (
    id uuid primary key,
    tenant_id uuid not null,
    actor varchar(200) not null,
    action varchar(80) not null,
    delivery_id uuid not null,
    occurred_at timestamptz not null
);
create or replace function reject_dispatch_audit_mutation() returns trigger language plpgsql as $$
begin raise exception 'dispatch_audit is append-only'; end $$;
create trigger dispatch_audit_no_update before update or delete on dispatch_audit
for each row execute function reject_dispatch_audit_mutation();
