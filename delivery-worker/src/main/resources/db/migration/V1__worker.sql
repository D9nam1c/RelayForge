create table worker_commands (
    command_id uuid primary key,
    delivery_id uuid not null,
    generation integer not null,
    attempt_number integer not null,
    status varchar(20) not null check (status in ('RECEIVED','COMPLETED')),
    lease_owner varchar(100),
    lease_until timestamptz,
    result_id uuid,
    received_at timestamptz not null,
    completed_at timestamptz,
    check ((lease_owner is null) = (lease_until is null))
);

create table result_outbox (
    result_id uuid primary key,
    command_id uuid not null unique references worker_commands(command_id),
    delivery_id uuid not null,
    tenant_id uuid not null,
    generation integer not null,
    attempt_number integer not null,
    outcome varchar(40) not null,
    http_status integer,
    latency_ms bigint not null,
    completed_at timestamptz not null,
    retry_after timestamptz,
    response_bytes bigint not null,
    detail varchar(160),
    created_at timestamptz not null,
    published_at timestamptz
);
create index worker_result_outbox_due_idx on result_outbox(created_at) where published_at is null;
