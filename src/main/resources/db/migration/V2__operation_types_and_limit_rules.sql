create table limit_management.operation_types (
    id uuid primary key,
    code varchar(64) not null,
    name varchar(160) not null,
    family_code varchar(64) not null,
    direction varchar(16) not null,
    enabled boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint operation_types_code_uk unique (code),
    constraint operation_types_code_not_blank check (length(trim(code)) > 0),
    constraint operation_types_name_not_blank check (length(trim(name)) > 0),
    constraint operation_types_family_not_blank check (length(trim(family_code)) > 0),
    constraint operation_types_direction_chk check (direction in ('IN', 'OUT', 'ALL'))
);

create table limit_management.limit_rules (
    id uuid primary key,
    code varchar(64) not null,
    version integer not null,
    name varchar(160) not null,
    operation_type_id uuid not null references limit_management.operation_types (id),
    target_type varchar(16) not null,
    metric varchar(16) not null,
    period varchar(16) not null,
    currency varchar(3),
    status varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    activated_at timestamptz,
    disabled_at timestamptz,
    constraint limit_rules_code_version_uk unique (code, version),
    constraint limit_rules_version_positive check (version >= 1),
    constraint limit_rules_code_not_blank check (length(trim(code)) > 0),
    constraint limit_rules_name_not_blank check (length(trim(name)) > 0),
    constraint limit_rules_target_type_chk check (target_type = 'PHONE'),
    constraint limit_rules_metric_chk check (metric in ('AMOUNT', 'COUNT')),
    constraint limit_rules_period_chk check (period in ('DAY', 'WEEK', 'MONTH')),
    constraint limit_rules_status_chk check (status in ('DRAFT', 'ACTIVE', 'DISABLED')),
    constraint limit_rules_currency_metric_chk check (
        (metric = 'AMOUNT' and currency = 'RUB')
        or (metric = 'COUNT' and currency is null)
    ),
    constraint limit_rules_activation_chk check (
        (status = 'DRAFT' and activated_at is null and disabled_at is null)
        or (status = 'ACTIVE' and activated_at is not null and disabled_at is null)
        or (status = 'DISABLED' and activated_at is not null and disabled_at is not null)
    )
);

create unique index limit_rules_one_draft_per_code_uk
    on limit_management.limit_rules (code)
    where status = 'DRAFT';

create index limit_rules_operation_type_idx
    on limit_management.limit_rules (operation_type_id, status, code);

insert into limit_management.operation_types
    (id, code, name, family_code, direction, enabled, created_at, updated_at)
values
    ('11111111-1111-1111-1111-111111111111', 'SBP_C2B', 'SBP C2B', 'SBP', 'IN', true, now(), now()),
    ('22222222-2222-2222-2222-222222222222', 'SBP_B2C', 'SBP B2C', 'SBP', 'OUT', true, now(), now())
on conflict (code) do nothing;
