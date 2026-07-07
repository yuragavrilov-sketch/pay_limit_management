-- Append-only audit log. Rows are inserted in the same transaction as the mutation
-- they describe; application code never updates or deletes audit_event rows.
create table limit_management.audit_event (
    id uuid primary key,
    entity_type varchar(64) not null,
    entity_id varchar(128) not null,
    action varchar(32) not null,
    actor_id varchar(128) not null,
    actor_name varchar(256),
    occurred_at timestamptz not null,
    before jsonb,
    after jsonb
);

create index audit_event_entity_idx
    on limit_management.audit_event (entity_type, entity_id, occurred_at desc);

create index audit_event_occurred_at_idx
    on limit_management.audit_event (occurred_at desc);
