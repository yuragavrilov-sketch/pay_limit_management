alter table limit_management.limit_assignments
    drop constraint limit_assignments_owner_type_chk,
    drop constraint limit_assignments_owner_id_not_blank,
    alter column owner_id drop not null,
    add constraint limit_assignments_owner_type_chk
        check (owner_type in ('GLOBAL', 'MERCHANT_GROUP', 'MERCHANT')),
    add constraint limit_assignments_owner_id_shape check (
        (owner_type = 'GLOBAL' and owner_id is null)
        or (owner_type in ('MERCHANT_GROUP', 'MERCHANT') and owner_id is not null and length(trim(owner_id)) > 0)
    );

-- Recreate the enabled no-overlap exclusion so GLOBAL rows (owner_id NULL) compare equal.
alter table limit_management.limit_assignments
    drop constraint limit_assignments_enabled_no_overlap;
alter table limit_management.limit_assignments
    add constraint limit_assignments_enabled_no_overlap
    exclude using gist (
        rule_id with =,
        owner_type with =,
        (coalesce(owner_id, '')) with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    )
    where (enabled = true);
