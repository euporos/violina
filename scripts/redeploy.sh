#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

CONFIG_PATH="${CONFIG_PATH:-/home/phylax/projects/festival_pg/directus_config}"
export CONFIG_PATH

# Previous HEAD passed by deploy-prod for rollback support
PREV_HEAD="${1:-}"

echo "=== Festival Deploy ==="
echo "Project: $PROJECT_DIR"
if [[ -n "$PREV_HEAD" ]]; then
    echo "Rollback target: ${PREV_HEAD:0:12}"
fi
echo ""

# Roll back to previous HEAD, reinstall deps, and rebuild
rollback() {
    if [[ -z "$PREV_HEAD" ]]; then
        echo "!!! No rollback target — aborting without rollback ==="
        return
    fi
    echo ""
    echo "!!! Rolling back to ${PREV_HEAD:0:12} ==="
    git reset --hard "$PREV_HEAD"
    npm install
    (cd directus && npm install)
    nix run .#build-directus-extensions
    nix run .#build
    echo "!!! Previous version restored ==="
}

# Step 1: Install dependencies
echo "--- npm install (app) ---"
if ! npm install; then
    rollback
    exit 1
fi

echo "--- npm install (directus) ---"
if ! (cd directus && npm install); then
    rollback
    exit 1
fi

# Step 2: Build Directus extensions
echo "--- Build Directus extensions ---"
if ! nix run .#build-directus-extensions; then
    rollback
    exit 1
fi

# Step 3: Prefetch Clojure git deps (uses SSH agent forwarding from deploy session)
echo "--- Prefetch Clojure deps ---"
if ! clj -P; then
    rollback
    exit 1
fi

# Step 4: Production build
echo "--- Build ---"
if ! nix run .#build; then
    rollback
    exit 1
fi

# Step 5: Stop services (brief downtime starts)
echo "--- Stopping services ---"
sudo systemctl stop festival.service
sudo systemctl stop festival-directus.service

# Step 6: Apply Directus schema (idempotent, database is quiescent)
echo "--- Schema apply ---"
(cd directus && npx directus schema apply --yes ../schema/snapshot.json)

# Step 7: Start services (downtime ends)
echo "--- Starting services ---"
sudo systemctl start festival-directus.service
sleep 3
sudo systemctl start festival.service

echo "=== Deploy complete ==="
