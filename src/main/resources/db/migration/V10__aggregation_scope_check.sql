alter table limit_management.limit_rules
    add constraint limit_rules_aggregation_scope_chk
    check (aggregation_scope is null or aggregation_scope in ('OWNER', 'TARGET'));
