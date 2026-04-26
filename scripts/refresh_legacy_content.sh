#!/usr/bin/env bash
# Refresh content rows + uploads from the legacy v9 prod site
# (violina-petrychenko.de) into the local PG `directus` DB.
#
# Assumes `bootstrap_legacy_db.sh` has already populated PG with the
# v11.17-shape schema. This script ONLY refreshes user-content rows
# (everything not prefixed `directus_`) and the uploaded files —
# system tables (users, roles, permissions, settings, fields, etc.)
# are left alone.
#
# Pipeline:
#   ssh tunnel: 13399 → prod 3306
#     │ pgloader (data only, truncate, content tables only)
#     ▼ overwrites content tables in PG $PGDATABASE
#   rsync prod:/.../directus/uploads/ → local directus/uploads/
#   TRUNCATE directus_sessions; restart Directus
#
# Wrapped via `nix run .#refresh-legacy` so PGHOST/PGPORT/PGUSER/PGDATABASE
# from the devShell pgEnvHook are in scope.

set -euo pipefail

REMOTE_HOST="phylax@netcup-vps-2-arm"
REMOTE_DIRECTUS="/home/phylax/projects/violina_v2/directus"
REMOTE_DB_NAME="violina"
REMOTE_DB_USER="violina"
TUNNEL_PORT="${TUNNEL_PORT:-13399}"
LOCAL_UPLOADS="directus/uploads"

# 0. Preflight
if ! pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" >/dev/null 2>&1; then
  echo "Postgres not reachable on ${PGHOST}:${PGPORT}. Start dev infra first: nix run .#dev" >&2
  exit 1
fi
if ! ssh -o ConnectTimeout=5 -o BatchMode=yes "$REMOTE_HOST" true >/dev/null 2>&1; then
  echo "Cannot ssh to $REMOTE_HOST (need passwordless / agent auth)" >&2
  exit 1
fi

# Need at least one user content table in local PG — otherwise bootstrap
# wasn't run (or the schema is unexpectedly empty).
if [[ -z "$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -At -c \
      "SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename NOT LIKE 'directus_%' LIMIT 1;")" ]]; then
  echo "No content tables in PG ${PGDATABASE}. Run bootstrap-legacy first." >&2
  exit 1
fi

# 1. Resolve prod DB password (same one-liner as nas-nixos backup_violina.sh).
echo "--- Resolving prod DB password ---"
DB_PASSWORD="$(ssh "$REMOTE_HOST" "grep '^DB_PASSWORD=' $REMOTE_DIRECTUS/.env | cut -d'\"' -f2")"
[[ -n "$DB_PASSWORD" ]] || { echo "Empty DB_PASSWORD from prod .env" >&2; exit 1; }
# URL-encode for the pgloader URI (pgloader's URI parser doesn't tolerate
# unescaped @, :, /, ?, # in passwords).
DB_PASSWORD_ENC="$(python3 -c \
  'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=""))' \
  "$DB_PASSWORD")"

# 2. Open SSH tunnel; trap closes it on exit.
echo "--- Opening SSH tunnel localhost:${TUNNEL_PORT} → ${REMOTE_HOST}:3306 ---"
ssh -fN -o ExitOnForwardFailure=yes \
    -L "127.0.0.1:${TUNNEL_PORT}:127.0.0.1:3306" "$REMOTE_HOST"
TUNNEL_PID="$(pgrep -f "${TUNNEL_PORT}:127.0.0.1:3306" | head -1 || true)"
cleanup() {
  [[ -n "${TUNNEL_PID:-}" ]] && kill "$TUNNEL_PID" 2>/dev/null || true
  [[ -n "${LOAD_FILE:-}" ]] && rm -f "$LOAD_FILE"
}
trap cleanup EXIT

# 3. Build the content-table list from local PG (single source of truth:
#    whatever bootstrap put there). Used as a regex alternation in the
#    pgloader INCLUDING ONLY filter so system tables are skipped.
TABLES="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -At <<'SQL'
SELECT tablename FROM pg_tables
WHERE schemaname = 'public'
  AND tablename NOT LIKE 'directus_%'
ORDER BY tablename;
SQL
)"
TABLE_REGEX="$(echo "$TABLES" | paste -sd '|')"
echo "    matching $(echo "$TABLES" | wc -l) content table(s)"

# 4. Generate per-run pgloader file. data only + truncate means: don't
#    touch DDL, just empty matched target tables and refill. INCLUDING
#    ONLY restricts the pump to content tables — directus_* on PG is
#    untouched.
LOAD_FILE="$(mktemp -t refresh-legacy.XXXXXX.load)"
cat >"$LOAD_FILE" <<EOF
LOAD DATABASE
     FROM      mysql://${REMOTE_DB_USER}:${DB_PASSWORD_ENC}@127.0.0.1:${TUNNEL_PORT}/${REMOTE_DB_NAME}
     INTO postgresql://directus:password@127.0.0.1:${PGPORT}/${PGDATABASE}

WITH data only, truncate, reset sequences, preserve index names,
     quote identifiers,
     include no drop, create no tables, create no indexes

INCLUDING ONLY TABLE NAMES MATCHING ~/^(${TABLE_REGEX})\$/

SET PostgreSQL PARAMETERS
     maintenance_work_mem to '128MB',
     work_mem to '12MB'

ALTER SCHEMA '${REMOTE_DB_NAME}' RENAME TO 'public'
;
EOF

# 5. Stop dev Directus before rewriting its DB.
pc_running() { process-compose process list >/dev/null 2>&1; }
if pc_running; then
  process-compose process stop directus >/dev/null 2>&1 || true
fi

# 6. Pump.
echo "--- pgloader: prod MariaDB → PG ${PGDATABASE} (content tables) ---"
pgloader "$LOAD_FILE"

# 7. Carry-over sessions cause an admin-UI redirect loop (same gotcha as
#    in pg_migrate.sh / bootstrap_legacy_db.sh).
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
     -v ON_ERROR_STOP=1 -c 'TRUNCATE directus_sessions;' >/dev/null

# 8. Pull uploads. --delete keeps the local mirror tight to prod;
#    if you've added local-only files in directus/uploads/, they go.
echo "--- rsync uploads ---"
mkdir -p "$LOCAL_UPLOADS"
rsync -az --delete --info=stats1,progress2 \
      "${REMOTE_HOST}:${REMOTE_DIRECTUS}/uploads/" "${LOCAL_UPLOADS}/"

# 9. Restart Directus.
if pc_running; then
  process-compose process start directus >/dev/null 2>&1 || true
fi

echo
echo "refresh-legacy complete. Content tables + uploads up to date."
