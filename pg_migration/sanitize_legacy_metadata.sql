-- Post-migration sanitation for the v9 (MySQL) → v11 (Postgres) bootstrap.
--
-- pgloader maps MySQL `BIGINT` to Postgres `bigint`. The Postgres driver then
-- serializes bigint values as JSON strings (precision-safe). The Directus 11
-- admin UI passes these values through `i18n.n(...)` to format dimensions,
-- file sizes, panel positions, etc. — and vue-i18n throws
-- `INVALID_ARGUMENT` (error code 17) when it gets a string instead of a
-- number. That kills `v-form` rendering: file/image fields show
-- "Unexpected Error", thumbnails don't load, and the form can't be edited.
--
-- A fresh Directus 11 install creates these columns as `integer`. This
-- script converts the user-facing numeric columns back to `integer` so the
-- driver returns them as JSON numbers. `id` / FK columns are left as
-- `bigint` to avoid touching referential integrity.

ALTER TABLE directus_files
  ALTER COLUMN width         TYPE integer,
  ALTER COLUMN height        TYPE integer,
  ALTER COLUMN filesize      TYPE integer,
  ALTER COLUMN duration      TYPE integer,
  ALTER COLUMN focal_point_x TYPE integer,
  ALTER COLUMN focal_point_y TYPE integer;

ALTER TABLE directus_panels
  ALTER COLUMN width      TYPE integer,
  ALTER COLUMN height     TYPE integer,
  ALTER COLUMN position_x TYPE integer,
  ALTER COLUMN position_y TYPE integer;

ALTER TABLE directus_operations
  ALTER COLUMN position_x TYPE integer,
  ALTER COLUMN position_y TYPE integer;

ALTER TABLE directus_presets
  ALTER COLUMN refresh_interval TYPE integer;

ALTER TABLE directus_settings
  ALTER COLUMN auth_login_attempts TYPE integer;

ALTER TABLE directus_shares
  ALTER COLUMN max_uses   TYPE integer,
  ALTER COLUMN times_used TYPE integer;
