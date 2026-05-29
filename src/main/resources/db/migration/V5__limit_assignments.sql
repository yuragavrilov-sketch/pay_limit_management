create table limit_management.limit_assignments (
    id uuid primary key,
    rule_id uuid not null,
    owner_type varchar(32) not null,
    owner_id varchar(128) not null,
    limit_mode varchar(16) not null,
    limit_value varchar(32),
    valid_from timestamptz not null,
    valid_to timestamptz,
    enabled boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint limit_assignments_rule_fk
        foreign key (rule_id) references limit_management.limit_rules (id),
    constraint limit_assignments_owner_type_chk check (owner_type in ('MERCHANT_GROUP', 'MERCHANT')),
    constraint limit_assignments_owner_id_not_blank check (length(trim(owner_id)) > 0),
    constraint limit_assignments_limit_mode_chk check (limit_mode in ('LIMITED', 'UNLIMITED', 'BLOCKED')),
    constraint limit_assignments_limit_value_chk check (
        (limit_mode = 'LIMITED' and limit_value is not null and limit_value ~ '^[0-9]+(\.[0-9]{1,2})?$')
        or (limit_mode in ('UNLIMITED', 'BLOCKED') and limit_value is null)
    ),
    constraint limit_assignments_period_order check (valid_to is null or valid_to > valid_from)
);

alter table limit_management.limit_assignments
    add constraint limit_assignments_enabled_no_overlap
    exclude using gist (
        rule_id with =,
        owner_type with =,
        owner_id with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    )
    where (enabled = true);

create index limit_assignments_rule_idx
    on limit_management.limit_assignments (rule_id, enabled, valid_from desc);

create index limit_assignments_owner_idx
    on limit_management.limit_assignments (owner_type, owner_id, enabled, valid_from desc);
