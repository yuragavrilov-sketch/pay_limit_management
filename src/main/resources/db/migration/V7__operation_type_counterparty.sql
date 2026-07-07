-- Reconcile operation_types to the §2.1 catalog (7 types, direction IN|OUT, counterparty_type).
alter table limit_management.operation_types
    add column counterparty_type varchar(16);

-- Remove non-spec, ALL-direction rows (SBP_C2C, PSR) before tightening the CHECK.
delete from limit_management.operation_types where code in ('SBP_C2C', 'PSR');

-- Fix AFT direction (spec: AFT is IN), add missing B2B account types.
update limit_management.operation_types set direction = 'IN' where code = 'AFT';

insert into limit_management.operation_types
    (id, code, name, family_code, direction, counterparty_type, enabled, sort_order, created_at, updated_at)
values
    ('88888888-8888-8888-8888-888888888888', 'SBP_B2B_IN',  'SBP B2B incoming', 'SBP', 'IN',  'ACCOUNT', true, 80, now(), now()),
    ('99999999-9999-9999-9999-999999999999', 'SBP_B2B_OUT', 'SBP B2B outgoing', 'SBP', 'OUT', 'ACCOUNT', true, 90, now(), now())
on conflict (code) do nothing;

update limit_management.operation_types set counterparty_type = case code
    when 'ECOM' then 'CARD'
    when 'AFT' then 'CARD'
    when 'OCT' then 'CARD'
    when 'SBP_C2B' then 'PHONE'
    when 'SBP_B2C' then 'PHONE'
    when 'SBP_B2B_IN' then 'ACCOUNT'
    when 'SBP_B2B_OUT' then 'ACCOUNT'
    else counterparty_type
end;

alter table limit_management.operation_types
    alter column counterparty_type set not null,
    drop constraint operation_types_direction_chk,
    add constraint operation_types_direction_chk check (direction in ('IN', 'OUT')),
    add constraint operation_types_counterparty_chk check (counterparty_type in ('CARD', 'PHONE', 'ACCOUNT'));
