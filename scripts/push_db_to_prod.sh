#!/usr/bin/env bash
set -euo pipefail

# Push the local Postgres `directus` DB to production.
# Destructive on prod: drops and recreates the prod directus database.
#
# Local connection: PGHOST/PGPORT/PGUSER/PGDATABASE from the devShell pgEnvHook.
# Prod connection: read DB_* vars from $CONFIG_PATH/.env on the server.

SERVER="phylax@netcup-vps-2-arm"
REMOTE_ENV="/home/phylax/projects/violina_macchiato/directus_config"
DUMP_LOCAL="/tmp/violina-directus-push-$(date -u +%Y%m%dT%H%M%SZ).sql"
DUMP_REMOTE="/tmp/$(basename "$DUMP_LOCAL")"

LOCAL_DB="${PGDATABASE:-directus}"
PROD_DB="$(ssh "$SERVER" "grep -E '^DB_DATABASE=' '$REMOTE_ENV' | cut -d= -f2- | tr -d '\"'" )"
[[ -n "$PROD_DB" ]] || { echo "Could not read DB_DATABASE from $REMOTE_ENV on $SERVER"; exit 1; }

echo "=== Push local DB → production ==="
echo "  Source: ${PGUSER:-?}@${PGHOST:-?}:${PGPORT:-?}/$LOCAL_DB"
echo "  Target: $SERVER → $PROD_DB"
echo ""
echo "This will DROP the production database '$PROD_DB' on $SERVER and replace"
echo "its contents with your local '$LOCAL_DB' DB. Production Directus will be"
echo "briefly stopped."
echo ""
read -r -p "Type 'yes' to continue: " confirm
[[ "$confirm" == "yes" ]] || { echo "Aborted."; exit 1; }

echo "--- Dumping local DB → $DUMP_LOCAL ---"
pg_dump --no-owner --no-privileges --clean --if-exists \
        --dbname="${PGDATABASE:-directus}" \
        > "$DUMP_LOCAL"

echo "--- Uploading dump ---"
scp "$DUMP_LOCAL" "$SERVER:$DUMP_REMOTE"

echo "--- Restoring on production ---"
ssh "$SERVER" bash -s "$DUMP_REMOTE" "$REMOTE_ENV" <<'REMOTE'
set -euo pipefail
DUMP="$1"
ENV_FILE="$2"

# Pull DB_* values out without sourcing (the env file may not be shell-safe).
get_env() {
  local key="$1"
  grep -E "^${key}=" "$ENV_FILE" | head -n1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

export PGHOST="$(get_env DB_HOST)";     PGHOST="${PGHOST:-localhost}"
export PGPORT="$(get_env DB_PORT)";     PGPORT="${PGPORT:-5432}"
export PGUSER="$(get_env DB_USER)"
export PGPASSWORD="$(get_env DB_PASSWORD)"
TARGET_DB="$(get_env DB_DATABASE)"
[[ -n "$PGUSER" && -n "$PGPASSWORD" && -n "$TARGET_DB" ]] || {
  echo "Missing DB_USER / DB_PASSWORD / DB_DATABASE in $ENV_FILE" >&2; exit 1; }

echo "    Stopping violina-macchiato-directus.service"
sudo systemctl stop violina-macchiato-directus.service

echo "    Resetting schema 'public' in $TARGET_DB"
# Drop and recreate the schema rather than the DB, since the directus role
# owns the schema but lacks CREATEDB. All Directus tables live in `public`.
psql -v ON_ERROR_STOP=1 -d "$TARGET_DB" <<SQL
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public AUTHORIZATION "$PGUSER";
GRANT ALL ON SCHEMA public TO "$PGUSER";
GRANT USAGE ON SCHEMA public TO PUBLIC;
SQL

echo "    Restoring dump"
psql -v ON_ERROR_STOP=1 -d "$TARGET_DB" -f "$DUMP" >/dev/null

rm -f "$DUMP"

echo "    Starting violina-macchiato-directus.service"
sudo systemctl start violina-macchiato-directus.service
REMOTE

rm -f "$DUMP_LOCAL"
echo "=== Done ==="
