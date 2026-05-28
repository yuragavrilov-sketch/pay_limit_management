create table limit_management.rule_manifests (
    id uuid primary key,
    version integer not null,
    status varchar(16) not null,
    checksum varchar(128) not null,
    rule_count integer not null,
    payload_json jsonb not null,
    created_at timestamptz not null,
    constraint rule_manifests_version_positive check (version >= 1),
    constraint rule_manifests_version_uk unique (version),
    constraint rule_manifests_checksum_uk unique (checksum),
    constraint rule_manifests_status_chk check (status = 'VALID'),
    constraint rule_manifests_rule_count_non_negative check (rule_count >= 0),
    constraint rule_manifests_checksum_shape_chk check (checksum like 'sha256:%')
);

create table limit_management.rule_manifest_rules (
    manifest_id uuid not null references limit_management.rule_manifests (id) on delete cascade,
    rule_id uuid not null references limit_management.limit_rules (id),
    rule_code varchar(64) not null,
    rule_version integer not null,
    position integer not null,
    payload_json jsonb not null,
    primary key (manifest_id, rule_id),
    constraint rule_manifest_rules_position_positive check (position >= 0),
    constraint rule_manifest_rules_rule_version_positive check (rule_version >= 1),
    constraint rule_manifest_rules_position_uk unique (manifest_id, position)
);

create index rule_manifests_created_at_idx
    on limit_management.rule_manifests (created_at desc);

create index rule_manifest_rules_rule_idx
    on limit_management.rule_manifest_rules (rule_id);
