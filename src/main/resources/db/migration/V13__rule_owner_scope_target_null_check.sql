-- Defense-in-depth on top of application validation: target_type must be set ONLY when
-- aggregation_scope = TARGET (converse of limit_rules_scope_target_chk from V8/V10).
-- `is not distinct from` correctly handles a NULL aggregation_scope (PER_OPERATION rules):
-- NULL is not distinct from 'TARGET' evaluates to false, so target_type is still required to be NULL.
alter table limit_management.limit_rules
    add constraint limit_rules_owner_scope_no_target_chk
    check (aggregation_scope is not distinct from 'TARGET' or target_type is null);
