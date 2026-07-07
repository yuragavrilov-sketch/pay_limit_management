-- v2 snapshot: manifests now carry schemaVersion in the payload; persist it on the header row too.
-- Historical rows (created before this migration) get the default of 2 — no prod data exists yet,
-- so this historical inaccuracy is acceptable (see stage-4 plan / task-1 brief).
alter table limit_management.runtime_manifests
    add column schema_version integer not null default 2;
