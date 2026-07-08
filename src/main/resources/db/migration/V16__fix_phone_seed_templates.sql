-- Forward-only data fix for the V15 seed (V15 is applied/immutable, so it is never edited).
-- The PODFT-PHONE-* DRAFT rules seeded by V15 carried card-worded error_message_template text
-- ("...на карту") copy-pasted from their CARD siblings; correct it to phone-appropriate wording
-- ("...на телефон"). Only DRAFT rows matched by code are touched -- these rules were never
-- activated (spec: activation is a manual operator action), so rewriting their template here is
-- safe and does not touch any immutable audit/manifest history. Placeholders (%d/%f/%s) are kept
-- unchanged. PODFT-PHONE-INTERVAL is not listed: its seeded template never mentioned "карту".

update limit_management.limit_rules
set error_message_template = 'Превышен ежедневный лимит выплат на телефон. Лимит %d, использовано %f, осталось количество выплат %s.',
    updated_at = now()
where code = 'PODFT-PHONE-COUNT-DAY'
  and status = 'DRAFT';

update limit_management.limit_rules
set error_message_template = 'Превышен ежемесячный лимит выплат на телефон. Лимит %d, использовано %f, осталось количество выплат %s.',
    updated_at = now()
where code = 'PODFT-PHONE-COUNT-MONTH'
  and status = 'DRAFT';

update limit_management.limit_rules
set error_message_template = 'Превышен ежедневный лимит выплат на телефон. Лимит %d, использовано %f, сумма текущей выплаты %s.',
    updated_at = now()
where code = 'PODFT-PHONE-AMOUNT-DAY'
  and status = 'DRAFT';

update limit_management.limit_rules
set error_message_template = 'Превышен ежемесячный лимит выплат на телефон. Лимит %d, использовано %f, сумма текущей выплаты %s.',
    updated_at = now()
where code = 'PODFT-PHONE-AMOUNT-MONTH'
  and status = 'DRAFT';
