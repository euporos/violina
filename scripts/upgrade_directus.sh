#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG_PATH="$(realpath "${CONFIG_PATH:-../directus_config}")"
export CONFIG_PATH

echo "=== Directus v11 Upgrade ==="
echo "--- Backup database ---"
pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f pre_upgrade_backup_$(date +%Y%m%d).sql

echo "--- Install Directus v11 ---"
(cd directus && npm install)

echo "--- Database migrate ---"
(cd directus && npx directus database migrate:latest)

echo "--- Export new schema ---"
(cd directus && npx directus schema snapshot --format json ../schema/snapshot.json)

echo "=== Upgrade complete ==="
