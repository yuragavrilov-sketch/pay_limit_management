-- Migration role must be allowed to create btree_gist, or the extension must be pre-provisioned by DBA.
create extension if not exists btree_gist;

create table limit_management.merchant_group_types (
    id uuid primary key,
    code varchar(64) not null,
    name varchar(160) not null,
    description text,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint merchant_group_types_code_uk unique (code),
    constraint merchant_group_types_code_not_blank check (length(trim(code)) > 0),
    constraint merchant_group_types_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.merchant_groups (
    id uuid primary key,
    type_id uuid not null references limit_management.merchant_group_types (id),
    code varchar(64) not null,
    name varchar(160) not null,
    description text,
    enabled boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint merchant_groups_id_type_uk unique (id, type_id),
    constraint merchant_groups_type_code_uk unique (type_id, code),
    constraint merchant_groups_code_not_blank check (length(trim(code)) > 0),
    constraint merchant_groups_name_not_blank check (length(trim(name)) > 0)
);

create table limit_management.merchant_group_memberships (
    id uuid primary key,
    merchant_id varchar(64) not null,
    group_id uuid not null,
    group_type_id uuid not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    created_at timestamptz not null,
    created_by varchar(160) not null,
    closed_at timestamptz,
    closed_by varchar(160),
    constraint merchant_group_memberships_group_fk
        foreign key (group_id, group_type_id)
        references limit_management.merchant_groups (id, type_id),
    constraint merchant_group_memberships_merchant_not_blank check (length(trim(merchant_id)) > 0),
    constraint merchant_group_memberships_created_by_not_blank check (length(trim(created_by)) > 0),
    constraint merchant_group_memberships_period_order check (valid_to is null or valid_to > valid_from),
    constraint merchant_group_memberships_closed_pair check (
        (closed_at is null and closed_by is null)
        or (closed_at is not null and closed_by is not null)
    )
);

alter table limit_management.merchant_group_memberships
    add constraint merchant_group_memberships_no_overlap
    exclude using gist (
        merchant_id with =,
        group_type_id with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    );

create index if not exists merchant_groups_type_idx
    on limit_management.merchant_groups (type_id, enabled, code);

create index if not exists merchant_group_memberships_merchant_idx
    on limit_management.merchant_group_memberships (merchant_id, group_type_id, valid_from desc);

create index if not exists merchant_group_memberships_group_idx
    on limit_management.merchant_group_memberships (group_id, valid_from desc);
