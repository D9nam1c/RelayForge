create table events (
    id uuid primary key,
    tenant_id uuid not null,
    idempotency_key varchar(200) not null,
    request_hash char(64) not null,
    event_type varchar(128) not null,
    schema_version integer not null check (schema_version > 0),
    source_id varchar(200) not null,
    occurred_at timestamptz not null,
    correlation_id uuid not null,
    causation_id uuid,
    traceparent varchar(512),
    content_type varchar(100) not null,
    payload bytea not null,
    accepted_at timestamptz not null,
    unique (tenant_id, idempotency_key)
);
create index events_tenant_time_idx on events(tenant_id, accepted_at desc);

create table outbox (
    id uuid primary key,
    event_id uuid not null unique references events(id),
    created_at timestamptz not null,
    published_at timestamptz
);
create index ingest_outbox_due_idx on outbox(created_at) where published_at is null;
