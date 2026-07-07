-- Junction: rule → operation types (replaces the single operation_selector_*).
create table limit_management.limit_rule_operation_type (
    rule_id uuid not null references limit_management.limit_rules (id) on delete cascade,
    operation_type_code varchar(64) not null,
    constraint limit_rule_operation_type_pk primary key (rule_id, operation_type_code),
    constraint limit_rule_operation_type_code_fk
        foreign key (operation_type_code) references limit_management.operation_types (code)
);

-- Backfill from the old single selector (only TYPE-selectors carried a concrete code).
insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
select id, operation_selector_value
from limit_management.limit_rules
where operation_selector_type = 'TYPE' and operation_selector_value is not null;

-- New rule columns.
alter table limit_management.limit_rules
    add column aggregation_scope varchar(16),
    add column interval_minutes integer,
    add column limit_value numeric(38, 2),
    add column error_message_template varchar(1024);

update limit_management.limit_rules
set error_message_template = 'Лимит превышен. Лимит %d, использовано %f, значение операции %s.'
where error_message_template is null;

-- Drop the retired single-selector columns and constraints.
alter table limit_management.limit_rules
    drop constraint if exists limit_rules_operation_selector_type_chk,
    drop constraint if exists limit_rules_operation_selector_value_chk,
    drop column operation_selector_type,
    drop column operation_selector_value;

-- Retarget CHECKs to the §2.1 model.
alter table limit_management.limit_rules
    drop constraint if exists limit_rules_direction_chk,
    drop constraint if exists limit_rules_target_type_chk,
    drop constraint if exists limit_rules_metric_chk,
    drop constraint if exists limit_rules_period_chk,
    drop constraint if exists limit_rules_currency_metric_chk,
    alter column target_type drop not null,
    alter column period drop not null,
    add constraint limit_rules_direction_chk check (direction in ('IN', 'OUT')),
    add constraint limit_rules_target_type_chk check (target_type is null or target_type in ('CARD', 'PHONE', 'ACCOUNT')),
    add constraint limit_rules_metric_chk check (metric in ('AMOUNT', 'COUNT', 'INTERVAL')),
    add constraint limit_rules_period_chk check (period is null or period in ('DAY', 'WEEK', 'MONTH', 'PER_OPERATION')),
    add constraint limit_rules_error_template_not_blank check (length(trim(error_message_template)) > 0),
    -- Validation 1: PER_OPERATION ⇒ AMOUNT, no aggregation scope, no target.
    add constraint limit_rules_per_operation_chk check (
        period is distinct from 'PER_OPERATION'
        or (metric = 'AMOUNT' and aggregation_scope is null and target_type is null)
    ),
    -- Validation 2: INTERVAL ⇒ TARGET scope, interval_minutes > 0, no period, no limit_value.
    add constraint limit_rules_interval_chk check (
        metric <> 'INTERVAL'
        or (aggregation_scope = 'TARGET' and interval_minutes is not null and interval_minutes > 0
            and period is null and limit_value is null)
    ),
    -- AMOUNT/COUNT ⇒ limit_value present; INTERVAL ⇒ absent (covered above).
    add constraint limit_rules_limit_value_chk check (
        (metric in ('AMOUNT', 'COUNT') and limit_value is not null)
        or (metric = 'INTERVAL' and limit_value is null)
    ),
    -- TARGET scope ⇒ target type present; OWNER ⇒ absent.
    add constraint limit_rules_scope_target_chk check (
        aggregation_scope is distinct from 'TARGET' or target_type is not null
    );

drop index if exists limit_management.limit_rules_selector_idx;
create index limit_rules_status_code_idx
    on limit_management.limit_rules (status, code);
