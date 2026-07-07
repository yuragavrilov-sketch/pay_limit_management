alter table limit_management.limit_assignments
    drop constraint if exists limit_assignments_limit_value_chk,
    drop column limit_value;
