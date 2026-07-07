-- Data seed (spec: Постановка §2/§4): first merchant group "ПОД/ФТ выплаты" plus its 11
-- payout-limit rules for OUT channels OCT (card) and SBP_B2C (phone).
--
-- Everything below is DRAFT / not enabled: rule status = 'DRAFT' (never ACTIVE), and the group
-- assignments are created with enabled = false. Activation of each rule (POST .../activate) and
-- compiling+scheduling the first runtime manifest are MANUAL operator actions performed after
-- this deploy — never a side effect of running this migration. Because
-- RuntimeManifestCompiler/PostgresLimitRuleRepository only ever select status = 'ACTIVE' rules
-- for compilation, a manifest compiled immediately after this migration runs will contain none
-- of these rules.
--
-- Limit values below (count/day=3, count/month=24, amount/operation=600000,
-- amount/day=600000, amount/month=1200000, interval=5 min) are a starting point per §4; the
-- operator reviews/adjusts them before activating each rule. All identifiers are synthetic
-- literal UUIDs — no PAN/phone/account data.

insert into limit_management.merchant_group_types
    (id, code, name, description, enabled, sort_order, created_at, updated_at)
values
    ('10000000-0000-0000-0000-000000000001', 'PODFT', 'ПОД/ФТ',
     'Группы мерчантов, к которым применяются лимиты противодействия отмыванию доходов и финансированию терроризма',
     true, 10, now(), now());

insert into limit_management.merchant_groups
    (id, type_id, code, name, description, enabled, created_at, updated_at)
values
    ('10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     'PODFT-PAYOUTS', 'ПОД/ФТ выплаты', 'Первая группа лимитов на выплатные каналы (OCT, SBP B2C)',
     true, now(), now());

insert into limit_management.limit_rules
    (id, code, version, name, direction,
     attribute_selector_type, attribute_selector_value, target_type,
     metric, period, aggregation_scope, currency, interval_minutes,
     limit_value, error_message_template,
     status, created_at, updated_at, activated_at, disabled_at)
values
    ('20000000-0000-0000-0000-000000000001', 'PODFT-CARD-COUNT-DAY', 1,
     'ПОД/ФТ: количество выплат на карту в сутки', 'OUT',
     'NONE', null, 'CARD',
     'COUNT', 'DAY', 'TARGET', null, null,
     3, 'Превышен ежедневный лимит выплат на карту. Лимит %d, использовано %f, осталось количество выплат %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000002', 'PODFT-PHONE-COUNT-DAY', 1,
     'ПОД/ФТ: количество выплат на телефон в сутки', 'OUT',
     'NONE', null, 'PHONE',
     'COUNT', 'DAY', 'TARGET', null, null,
     3, 'Превышен ежедневный лимит выплат на карту. Лимит %d, использовано %f, осталось количество выплат %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000003', 'PODFT-CARD-COUNT-MONTH', 1,
     'ПОД/ФТ: количество выплат на карту в месяц', 'OUT',
     'NONE', null, 'CARD',
     'COUNT', 'MONTH', 'TARGET', null, null,
     24, 'Превышен ежемесячный лимит выплат на карту. Лимит %d, использовано %f, осталось количество выплат %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000004', 'PODFT-PHONE-COUNT-MONTH', 1,
     'ПОД/ФТ: количество выплат на телефон в месяц', 'OUT',
     'NONE', null, 'PHONE',
     'COUNT', 'MONTH', 'TARGET', null, null,
     24, 'Превышен ежемесячный лимит выплат на карту. Лимит %d, использовано %f, осталось количество выплат %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000005', 'PODFT-AMOUNT-PER-OPERATION', 1,
     'ПОД/ФТ: сумма одной выплаты', 'OUT',
     'NONE', null, null,
     'AMOUNT', 'PER_OPERATION', null, 'RUB', null,
     600000, 'Превышен лимит выплаты на карту. Лимит %d, использовано %f, сумма текущей выплаты %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000006', 'PODFT-CARD-AMOUNT-DAY', 1,
     'ПОД/ФТ: сумма выплат на карту в сутки', 'OUT',
     'NONE', null, 'CARD',
     'AMOUNT', 'DAY', 'TARGET', 'RUB', null,
     600000, 'Превышен ежедневный лимит выплат на карту. Лимит %d, использовано %f, сумма текущей выплаты %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000007', 'PODFT-PHONE-AMOUNT-DAY', 1,
     'ПОД/ФТ: сумма выплат на телефон в сутки', 'OUT',
     'NONE', null, 'PHONE',
     'AMOUNT', 'DAY', 'TARGET', 'RUB', null,
     600000, 'Превышен ежедневный лимит выплат на карту. Лимит %d, использовано %f, сумма текущей выплаты %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000008', 'PODFT-CARD-AMOUNT-MONTH', 1,
     'ПОД/ФТ: сумма выплат на карту в месяц', 'OUT',
     'NONE', null, 'CARD',
     'AMOUNT', 'MONTH', 'TARGET', 'RUB', null,
     1200000, 'Превышен ежемесячный лимит выплат на карту. Лимит %d, использовано %f, сумма текущей выплаты %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000009', 'PODFT-PHONE-AMOUNT-MONTH', 1,
     'ПОД/ФТ: сумма выплат на телефон в месяц', 'OUT',
     'NONE', null, 'PHONE',
     'AMOUNT', 'MONTH', 'TARGET', 'RUB', null,
     1200000, 'Превышен ежемесячный лимит выплат на карту. Лимит %d, использовано %f, сумма текущей выплаты %s.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000010', 'PODFT-CARD-INTERVAL', 1,
     'ПОД/ФТ: частота выплат на карту', 'OUT',
     'NONE', null, 'CARD',
     'INTERVAL', null, 'TARGET', null, 5,
     null, 'Превышен лимит частоты выплат, повторите выплату позже.',
     'DRAFT', now(), now(), null, null),

    ('20000000-0000-0000-0000-000000000011', 'PODFT-PHONE-INTERVAL', 1,
     'ПОД/ФТ: частота выплат на телефон', 'OUT',
     'NONE', null, 'PHONE',
     'INTERVAL', null, 'TARGET', null, 5,
     null, 'Превышен лимит частоты выплат, повторите выплату позже.',
     'DRAFT', now(), now(), null, null);

insert into limit_management.limit_rule_operation_type (rule_id, operation_type_code)
values
    ('20000000-0000-0000-0000-000000000001', 'OCT'),
    ('20000000-0000-0000-0000-000000000002', 'SBP_B2C'),
    ('20000000-0000-0000-0000-000000000003', 'OCT'),
    ('20000000-0000-0000-0000-000000000004', 'SBP_B2C'),
    ('20000000-0000-0000-0000-000000000005', 'OCT'),
    ('20000000-0000-0000-0000-000000000005', 'SBP_B2C'),
    ('20000000-0000-0000-0000-000000000006', 'OCT'),
    ('20000000-0000-0000-0000-000000000007', 'SBP_B2C'),
    ('20000000-0000-0000-0000-000000000008', 'OCT'),
    ('20000000-0000-0000-0000-000000000009', 'SBP_B2C'),
    ('20000000-0000-0000-0000-000000000010', 'OCT'),
    ('20000000-0000-0000-0000-000000000011', 'SBP_B2C');

-- Not-enabled MERCHANT_GROUP assignments to the seeded group, one per rule. enabled = false keeps
-- them out of the GiST no-overlap exclusion (which only guards enabled = true rows) and out of
-- compiled manifests (only enabled assignments are read for compilation) — activation is manual.
insert into limit_management.limit_assignments
    (id, rule_id, owner_type, owner_id, limit_mode, valid_from, valid_to, enabled, created_at, updated_at)
values
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000004',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000007',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000008',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000009',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000010',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now()),
    ('30000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000011',
     'MERCHANT_GROUP', '10000000-0000-0000-0000-000000000002', 'LIMITED', now(), null, false, now(), now());
