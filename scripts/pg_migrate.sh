#!/usr/bin/env bash
# Rebuild the local PostgreSQL Directus DB from the current MariaDB prod clone.
# Idempotent: safe to re-run. Also the tool used at the Phase 5 cutover to
# produce a fresh local PG DB for pg_dump → scp → pg_restore onto the server.
#
# Preconditions: both `mariadb` and `postgres` dev processes are running
# (via `nix run .#dev`). directus/.env must be flipped to DB_CLIENT=pg.
#
# PGHOST/PGPORT/PGUSER/PGDATABASE come from the default devShell's pgEnvHook
# (the pg-migrate app wraps this script in `nix develop .#default --command`).

set -euo pipefail

# 0. Preflight
mysql -u root -e 'SELECT 1' >/dev/null
pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" >/dev/null

# 1. Fresh prod clone into MariaDB
bash scripts/clone_production_db.sh

# Stop Directus before rewriting its DB so it doesn't fight the bootstrap.
# process-compose's REST API is on $PC_PORT_NUM (default 8080); fall through
# silently when process-compose isn't running (e.g. CI, server cutover run).
pc_running() { process-compose process list >/dev/null 2>&1; }
if pc_running; then
  process-compose process stop directus >/dev/null 2>&1 || true
fi

# 2. Reset PG target — drop/recreate role and DB
psql -d postgres -v ON_ERROR_STOP=1 <<SQL
DROP DATABASE IF EXISTS directus;
DROP ROLE     IF EXISTS directus;
CREATE ROLE directus LOGIN PASSWORD 'password';
CREATE DATABASE directus OWNER directus;
SQL

# 3. Bootstrap Directus system tables, then push user collections.
#    Force the DB_* env vars so this script doesn't depend on whatever the
#    developer's gitignored directus/.env happens to contain — critical at
#    the Phase 5 cutover too.
export DB_CLIENT=pg
export DB_HOST="$PGHOST"
export DB_PORT="$PGPORT"
export DB_DATABASE=directus
export DB_USER=directus
export DB_PASSWORD=password
(cd directus && npx directus bootstrap)
(cd directus && npx directus schema apply --yes ../schema/snapshot.json)

# 4. Pump data MySQL → PG
pgloader pg_migration/festival.load

# 4a. Drop carried-over sessions — stale rows from the MariaDB dump cause a
#     "Admin User is currently authenticated → Continue" redirect loop in the
#     admin UI. Fresh logins create new session rows anyway.
psql -d "$PGDATABASE" -v ON_ERROR_STOP=1 -c 'TRUNCATE directus_sessions;'

# 5. Bring Directus back up against the populated PG DB
if pc_running; then
  process-compose process start directus >/dev/null 2>&1 || true
fi

echo "pg-migrate complete. Directus now on PG (${PGDATABASE} @ ${PGHOST}:${PGPORT})."
