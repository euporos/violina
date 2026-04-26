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

# 2. Stepwise Directus migrations: v9 → v10 → v11.
#    Run each major's binary against the same DB so its built-in migrations
#    upgrade the schema. Per-major node_modules are cached under ~/.cache.
#
#    Why a sub-nix-shell with Node 20 + Python 3.11: Directus 10/11 pull in
#    `isolated-vm`, whose pinned C++ source doesn't compile against Node 22's
#    V8 headers, and node-gyp needs Python's distutils (gone in 3.12+).
#    Node 20 + Py 3.11 is the sweet spot. (v9 has no native deps; using the
#    same shell for all three is just simpler than splitting.)
echo "--- Stepwise Directus migrations: v9 → v10 → v11 ---"
export SRC_DB CACHE_ROOT
nix-shell -p nodejs_20 python311 --run '
  set -euo pipefail
  export DB_CLIENT=mysql
  export DB_HOST=127.0.0.1
  export DB_PORT=3306
  export DB_DATABASE="$SRC_DB"
  export DB_USER=directus
  export DB_PASSWORD=password
  # KEY/SECRET are required to load Directus but throwaway for migrate
  # (they only sign tokens, not data).
  export KEY="$(uuidgen)"
  export SECRET="$(uuidgen)"

  for V in 9 10 11; do
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

if pc_running; then
  process-compose process start directus >/dev/null 2>&1 || true
fi

echo
echo "bootstrap-legacy complete. PG ${PGDATABASE} @ ${PGHOST}:${PGPORT}."
