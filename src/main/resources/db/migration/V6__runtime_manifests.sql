create table limit_management.runtime_manifests (
    id uuid primary key,
    version integer not null,
    status varchar(16) not null,
    checksum varchar(128) not null,
    created_at timestamptz not null,
    effective_from timestamptz not null,
    rule_count integer not null,
    assignment_count integer not null,
    membership_count integer not null,
    payload_json jsonb not null,
    constraint runtime_manifests_version_positive check (version >= 1),
    constraint runtime_manifests_version_uk unique (version),
    constraint runtime_manifests_checksum_uk unique (checksum),
    constraint runtime_manifests_status_chk check (status = 'VALID'),
    constraint runtime_manifests_rule_count_non_negative check (rule_count >= 0),
    constraint runtime_manifests_assignment_count_non_negative check (assignment_count >= 0),
    constraint runtime_manifests_membership_count_non_negative check (membership_count >= 0),
    constraint runtime_manifests_checksum_shape_chk check (checksum like 'sha256:%')
);

create table limit_management.runtime_manifest_rules (
    manifest_id uuid not null references limit_management.runtime_manifests (id) on delete cascade,
    rule_id uuid not null references limit_management.limit_rules (id),
    position integer not null,
    payload_json jsonb not null,
    primary key (manifest_id, rule_id),
    constraint runtime_manifest_rules_position_non_negative check (position >= 0),
    constraint runtime_manifest_rules_position_uk unique (manifest_id, position)
);

create table limit_management.runtime_manifest_assignments (
    manifest_id uuid not null references limit_management.runtime_manifests (id) on delete cascade,
    assignment_id uuid not null references limit_management.limit_assignments (id),
    position integer not null,
    payload_json jsonb not null,
    primary key (manifest_id, assignment_id),
    constraint runtime_manifest_assignments_position_non_negative check (position >= 0),
    constraint runtime_manifest_assignments_position_uk unique (manifest_id, position)
);

create table limit_management.runtime_manifest_memberships (
    manifest_id uuid not null references limit_management.runtime_manifests (id) on delete cascade,
    membership_id uuid not null references limit_management.merchant_group_memberships (id),
    position integer not null,
    payload_json jsonb not null,
    primary key (manifest_id, membership_id),
    constraint runtime_manifest_memberships_position_non_negative check (position >= 0),
    constraint runtime_manifest_memberships_position_uk unique (manifest_id, position)
);

create index runtime_manifests_effective_idx
    on limit_management.runtime_manifests (effective_from desc, version desc);

create index runtime_manifests_scheduled_idx
    on limit_management.runtime_manifests (effective_from asc, version asc);
