create table limit_management.operation_families (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint operation_families_code_not_blank check (length(trim(code)) > 0),
    constraint operation_families_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.payment_systems (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint payment_systems_code_not_blank check (length(trim(code)) > 0),
    constraint payment_systems_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.issuer_countries (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint issuer_countries_code_not_blank check (length(trim(code)) > 0),
    constraint issuer_countries_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.issuer_banks (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint issuer_banks_code_not_blank check (length(trim(code)) > 0),
    constraint issuer_banks_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.bins (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint bins_code_not_blank check (length(trim(code)) > 0),
    constraint bins_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.card_types (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint card_types_code_not_blank check (length(trim(code)) > 0),
    constraint card_types_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.card_levels (
    code varchar(64) primary key,
    name varchar(160) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint card_levels_code_not_blank check (length(trim(code)) > 0),
    constraint card_levels_name_not_blank check (length(trim(name)) > 0)
);

insert into limit_management.operation_families (code, name, enabled, sort_order, created_at, updated_at)
values
    ('SBP', 'SBP', true, 10, now(), now()),
    ('CARD', 'Card payments', true, 20, now(), now())
on conflict (code) do nothing;

insert into limit_management.payment_systems (code, name, enabled, sort_order, created_at, updated_at)
values
    ('MIR', 'MIR', true, 10, now(), now()),
    ('VISA', 'VISA', true, 20, now(), now()),
    ('MASTERCARD', 'Mastercard', true, 30, now(), now()),
    ('UNIONPAY', 'UnionPay', true, 40, now(), now())
on conflict (code) do nothing;

insert into limit_management.issuer_countries (code, name, enabled, sort_order, created_at, updated_at)
values
    ('RU', 'Russia', true, 10, now(), now())
on conflict (code) do nothing;

insert into limit_management.issuer_banks (code, name, enabled, sort_order, created_at, updated_at)
values
    ('TKB', 'TKB Bank', true, 10, now(), now())
on conflict (code) do nothing;

insert into limit_management.bins (code, name, enabled, sort_order, created_at, updated_at)
values
    ('220220', 'Synthetic MIR BIN 220220', true, 10, now(), now()),
    ('411111', 'Synthetic VISA BIN 411111', true, 20, now(), now())
on conflict (code) do nothing;

insert into limit_management.card_types (code, name, enabled, sort_order, created_at, updated_at)
values
    ('DEBIT', 'Debit', true, 10, now(), now()),
    ('CREDIT', 'Credit', true, 20, now(), now())
on conflict (code) do nothing;

insert into limit_management.card_levels (code, name, enabled, sort_order, created_at, updated_at)
values
    ('STANDARD', 'Standard', true, 10, now(), now()),
    ('GOLD', 'Gold', true, 20, now(), now()),
    ('PLATINUM', 'Platinum', true, 30, now(), now())
on conflict (code) do nothing;

alter table limit_management.operation_types
    add column if not exists sort_order integer not null default 0;

update limit_management.operation_types
set sort_order = case code
    when 'SBP_C2B' then 10
    when 'SBP_B2C' then 20
    else sort_order
end
where code in ('SBP_C2B', 'SBP_B2C');

insert into limit_management.operation_types
    (id, code, name, family_code, direction, enabled, sort_order, created_at, updated_at)
values
    ('33333333-3333-3333-3333-333333333333', 'SBP_C2C', 'SBP C2C', 'SBP', 'ALL', true, 30, now(), now()),
    ('44444444-4444-4444-4444-444444444444', 'ECOM', 'E-commerce card payment', 'CARD', 'IN', true, 40, now(), now()),
    ('55555555-5555-5555-5555-555555555555', 'AFT', 'Account funding transaction', 'CARD', 'OUT', true, 50, now(), now()),
    ('66666666-6666-6666-6666-666666666666', 'OCT', 'Original credit transaction', 'CARD', 'OUT', true, 60, now(), now()),
    ('77777777-7777-7777-7777-777777777777', 'PSR', 'Payment status request', 'CARD', 'ALL', true, 70, now(), now())
on conflict (code) do nothing;

alter table limit_management.operation_types
    add constraint operation_types_family_fk
    foreign key (family_code) references limit_management.operation_families (code);

alter table limit_management.limit_rules
    add column operation_selector_type varchar(16),
    add column operation_selector_value varchar(64),
    add column attribute_selector_type varchar(32),
    add column attribute_selector_value varchar(64),
    add column direction varchar(16),
    add column target_type_new varchar(16);

update limit_management.limit_rules r
set operation_selector_type = 'TYPE',
    operation_selector_value = o.code,
    attribute_selector_type = 'NONE',
    attribute_selector_value = null,
    direction = o.direction,
    target_type_new = r.target_type
from limit_management.operation_types o
where r.operation_type_id = o.id;

alter table limit_management.limit_rules
    alter column operation_selector_type set not null,
    alter column attribute_selector_type set not null,
    alter column direction set not null,
    alter column target_type_new set not null;

alter table limit_management.limit_rules
    drop constraint if exists limit_rules_operation_type_id_fkey,
    drop constraint if exists limit_rules_target_type_chk,
    drop column operation_type_id,
    drop column target_type;

alter table limit_management.limit_rules
    rename column target_type_new to target_type;

alter table limit_management.limit_rules
    add constraint limit_rules_operation_selector_type_chk check (operation_selector_type in ('ANY', 'FAMILY', 'TYPE')),
    add constraint limit_rules_attribute_selector_type_chk check (attribute_selector_type in ('NONE', 'PAYMENT_SYSTEM', 'ISSUER_COUNTRY', 'BIN', 'BANK', 'CARD_TYPE', 'CARD_LEVEL')),
    add constraint limit_rules_direction_chk check (direction in ('IN', 'OUT', 'ALL')),
    add constraint limit_rules_target_type_chk check (target_type in ('ANY', 'CARD', 'PHONE')),
    add constraint limit_rules_operation_selector_value_chk check (
        (operation_selector_type = 'ANY' and operation_selector_value is null)
        or (operation_selector_type in ('FAMILY', 'TYPE') and operation_selector_value is not null)
    ),
    add constraint limit_rules_attribute_selector_value_chk check (
        (attribute_selector_type = 'NONE' and attribute_selector_value is null)
        or (attribute_selector_type <> 'NONE' and attribute_selector_value is not null)
    );

drop index if exists limit_management.limit_rules_operation_type_idx;

create index limit_rules_selector_idx
    on limit_management.limit_rules (operation_selector_type, operation_selector_value, status, code);
