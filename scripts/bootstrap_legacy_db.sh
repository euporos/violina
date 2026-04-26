#!/usr/bin/env bash
# Bootstrap the local PG `directus` DB from the legacy v9 MariaDB dump of
# violina-petrychenko.de. One-time per project life (or after a content-shape
# drift). For ongoing content refreshes, a separate (yet-to-be-written) script
# will pull just the row data from prod into PG.
#
# Pipeline:
#   <local v9 dump>.sql
#     │ mysql restore
#     ▼
#   MariaDB violina_legacy
#     │ npx directus@^9  database migrate:latest
#     │ npx directus@^10 database migrate:latest
#     │ npx directus@^11 database migrate:latest
#     ▼
#   MariaDB violina_legacy (now v11 shape)
#     │ pgloader (creates tables, indexes, FKs)
#     ▼
#   PG $PGDATABASE
#     │ TRUNCATE directus_sessions
#     ▼
#   done
#
# Idempotent: drops/recreates both the MariaDB DB and the PG DB on each run.
# Wrapped via `nix run .#bootstrap-legacy` so PGHOST/PGPORT/PGUSER/PGDATABASE
# from the devShell pgEnvHook are in scope.

set -euo pipefail

DUMP="${1:-violina_2026-04-26T000032Z.sql}"
SRC_DB=violina_legacy
CACHE_ROOT="${HOME}/.cache/violina-import"

# 0. Preflight
[[ -f $DUMP ]] || { echo "Dump file not found: $DUMP" >&2; exit 1; }
if ! mysql -u root -e 'SELECT 1' >/dev/null 2>&1; then
  echo "MariaDB not reachable. Start dev infra first: nix run .#dev" >&2
  exit 1
fi
if ! pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" >/dev/null 2>&1; then
  echo "Postgres not reachable on ${PGHOST}:${PGPORT}. Start dev infra first: nix run .#dev" >&2
  exit 1
fi

# Stop the dev Directus before we rewrite its DB.
pc_running() { process-compose process list >/dev/null 2>&1; }
if pc_running; then
  process-compose process stop directus >/dev/null 2>&1 || true
fi

# 1. Restore dump into a fresh local MariaDB DB
echo "--- Restoring dump → MariaDB ${SRC_DB} ---"
mysql -u root -e "DROP DATABASE IF EXISTS \`${SRC_DB}\`; CREATE DATABASE \`${SRC_DB}\`;"
# Strip leading CREATE DATABASE / USE so the dump's original name doesn't override us.
sed -E '/^CREATE DATABASE.*violina/Id; /^USE `?violina`?/Id' "$DUMP" \
  | mysql -u root "${SRC_DB}"
mysql -u root <<SQL
CREATE USER IF NOT EXISTS 'directus'@'localhost' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON \`${SRC_DB}\`.* TO 'directus'@'localhost';
FLUSH PRIVILEGES;
SQL

# 2. Stepwise Directus migrations: v9 → v10 → v11, all against MariaDB.
#    Doing all three legs in MariaDB before pgloader runs keeps the
#    schema self-consistent: MariaDB has no `uuid` type (uses char(36)
#    everywhere), and pgloader transcribes that uniformly to PG
#    `character`. If we instead ran the v11.17 leg against PG *after*
#    pgloader, new FKs added by recent migrations (e.g. Add Deployment)
#    would be declared `uuid` while existing pgloader-created columns
#    are `character` — incompatible-type FK error.
#
#    Version pinning:
#    - v9 / v10: cached scratch installs in ~/.cache (any patch level
#      that resolves to the major is fine; nothing later in the pipeline
#      depends on these specific versions).
#    - v11: use the repo's own directus/ install — pinned via
#      directus/package.json + lockfile to the version this project
#      boots, so the post-pgloader Directus startup finds exactly the
#      schema migrations it expects (incl. things like `searchable` on
#      directus_fields, added in 11.4+).
#
#    Native build env:
#    - v10's `isolated-vm` doesn't compile against Node 22's V8 headers
#      and node-gyp needs Python's distutils (gone in 3.12+). The v9/v10
#      installs run inside a Node 20 / Python 3.11 sub-shell.
#    - v11 (repo install) was already built by `nix run .#setup` under
#      Node 22 — its native bindings are ABI-bound to Node 22, so we
#      invoke it from the outer dev shell, NOT inside the Node 20
#      sub-shell.
echo "--- Stepwise Directus migrations: v9 → v10 → v11 ---"
# Build a throwaway env file with the MariaDB connection. Directus
# (`@directus/env`) loads its config file LAST, so file values override
# process.env — meaning the existing directus/.env (which points at PG)
# would otherwise hijack these migrations. Point CONFIG_PATH at our
# temp file to win.
TMP_ENV="$(mktemp -t bootstrap-legacy-env.XXXXXX)"
trap 'rm -f "$TMP_ENV"' EXIT
cat >"$TMP_ENV" <<EOF
DB_CLIENT=mysql
DB_HOST=127.0.0.1
DB_PORT=3306
DB_DATABASE=${SRC_DB}
DB_USER=directus
DB_PASSWORD=password
KEY=$(uuidgen)
SECRET=$(uuidgen)
EOF
export CONFIG_PATH="$TMP_ENV"

# v9 + v10 in a Node 20 / Python 3.11 sub-shell.
export SRC_DB CACHE_ROOT CONFIG_PATH
nix-shell -p nodejs_20 python311 --run '
  set -euo pipefail
  for V in 9 10; do
    dir="$CACHE_ROOT/dx-v$V"
    if [[ ! -d "$dir/node_modules" ]]; then
      echo "  installing directus@^$V into $dir"
      mkdir -p "$dir"
      (cd "$dir" \
        && npm init -y >/dev/null \
        && npm i --silent --no-audit --no-fund --engine-strict=false \
             "directus@^$V" mysql2)
    fi
    echo "  migrating via directus@^$V"
    (cd "$dir" && npx --no-install directus database migrate:latest)
  done
'

# v11 — repo's own directus/ install, still pointed at MariaDB
# via CONFIG_PATH (which @directus/env loads in preference to
# directus/.env).
echo "  migrating via repo's own directus/ (v11)"
(cd directus && npx --no-install directus database migrate:latest)

# 3. Reset the PG target (drop/recreate role + DB).
echo "--- Resetting PG ${PGDATABASE} ---"
psql -d postgres -v ON_ERROR_STOP=1 <<SQL
DROP DATABASE IF EXISTS ${PGDATABASE};
DROP ROLE     IF EXISTS directus;
CREATE ROLE directus LOGIN PASSWORD 'password';
CREATE DATABASE ${PGDATABASE} OWNER directus;
SQL

# 4. Pump MariaDB → PG (creates tables, indexes, FKs from MariaDB schema).
echo "--- pgloader: MariaDB ${SRC_DB} → PG ${PGDATABASE} ---"
pgloader pg_migration/violina_legacy.load

# 5. Drop carried-over sessions; otherwise the admin UI hits a redirect loop
#    (same gotcha as in scripts/pg_migrate.sh).
psql -d "${PGDATABASE}" -v ON_ERROR_STOP=1 -c 'TRUNCATE directus_sessions;'

# 6. Narrow user-facing numeric columns from bigint → integer. pgloader maps
#    MySQL BIGINT to PG bigint, which the PG driver serializes as JSON
#    strings. The admin UI then feeds those strings to vue-i18n's number
#    formatter and the form crashes (INVALID_ARGUMENT, error code 17). See
#    pg_migration/sanitize_legacy_metadata.sql for the full rationale.
echo "--- Sanitizing legacy metadata (bigint → integer for UI numerics) ---"
psql -d "${PGDATABASE}" -v ON_ERROR_STOP=1 \
     -f pg_migration/sanitize_legacy_metadata.sql

if pc_running; then
  process-compose process start directus >/dev/null 2>&1 || true
fi

echo
echo "bootstrap-legacy complete. PG ${PGDATABASE} @ ${PGHOST}:${PGPORT}."
