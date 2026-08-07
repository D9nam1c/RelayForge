create table tenants (
    id uuid primary key,
    slug varchar(80) not null unique,
    display_name varchar(160) not null,
    created_at timestamptz not null
);

create table endpoints (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    name varchar(120) not null,
    url varchar(2048) not null,
    current_key_id varchar(100) not null,
    current_ciphertext text not null,
    previous_key_id varchar(100),
    previous_ciphertext text,
    previous_valid_until timestamptz,
    max_attempts integer not null check (max_attempts between 1 and 25),
    initial_delay_ms bigint not null check (initial_delay_ms > 0),
    max_delay_ms bigint not null check (max_delay_ms >= initial_delay_ms),
    max_retry_after_ms bigint not null check (max_retry_after_ms > 0),
    endpoint_concurrency integer not null check (endpoint_concurrency between 1 and 100),
    preserve_order boolean not null,
    enabled boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    check (not preserve_order or endpoint_concurrency = 1),
    check ((previous_key_id is null and previous_ciphertext is null and previous_valid_until is null)
        or (previous_key_id is not null and previous_ciphertext is not null and previous_valid_until is not null))
);
create index endpoints_tenant_idx on endpoints(tenant_id, id);

create table subscriptions (
    id uuid primary key,
    tenant_id uuid not null references tenants(id),
    endpoint_id uuid not null references endpoints(id),
    event_type varchar(128) not null,
    filter_json jsonb not null default '{}'::jsonb,
    enabled boolean not null,
    created_at timestamptz not null,
    unique (tenant_id, endpoint_id, event_type)
);
create index subscriptions_match_idx on subscriptions(tenant_id, event_type) where enabled;

create table audit_log (
    id uuid primary key,
    tenant_id uuid not null,
    actor varchar(200) not null,
    action varchar(80) not null,
    resource_type varchar(80) not null,
    resource_id uuid not null,
    detail varchar(500),
    occurred_at timestamptz not null
);
create index audit_tenant_time_idx on audit_log(tenant_id, occurred_at desc);

insert into tenants(id, slug, display_name, created_at) values
  ('11111111-1111-1111-1111-111111111111', 'acme', 'Acme Demo', now()),
  ('22222222-2222-2222-2222-222222222222', 'globex', 'Globex Demo', now());

create or replace function reject_audit_mutation() returns trigger language plpgsql as $$
begin raise exception 'audit_log is append-only'; end $$;
create trigger audit_no_update before update or delete on audit_log
for each row execute function reject_audit_mutation();
