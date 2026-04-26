{ pkgs, flake-utils, commonPackages }:

let
  mkApp = name: text: flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      inherit name text;
      runtimeInputs = commonPackages ++ [ pkgs.zip ];
    };
  };
in
{
  # Launch all dev processes via process-compose
  dev = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "festival-dev" ''
      exec nix develop .#default --command ${pkgs.process-compose}/bin/process-compose up -f process-compose.yaml
    '';
  };

  # Build all Directus extensions
  build-directus-extensions = mkApp "festival-build-directus-extensions" ''
    for ext in directus/extensions/directus-extension-*/; do
      if [ -f "$ext/package.json" ]; then
        echo "Building extension: $ext"
        (cd "$ext" && npx --package=@directus/extensions-sdk directus-extension build)
      fi
    done
  '';

  # Initial project setup
  setup = mkApp "festival-setup" ''
    npm install
    (cd directus && npm install)
    nix run .#build-directus-extensions
    mkdir -p export public public/from_reservoir
  '';

  # Process and resize reservoir images
  process-reservoir = mkApp "festival-process-reservoir" ''
    clj -X:process-reservoir
    bash scripts/resize_images.sh reservoir/imgs/*.JPG
  '';

  # Full production build
  build = mkApp "festival-build" ''
    export NODE_ENV=production
    rm -rf .shadow-cljs
    rm -rf public/compiled/*
    clj -X:gen-directus-links
    clj -X:process-reservoir
    bash scripts/resize_images.sh reservoir/imgs/*.JPG
    npx vite build
    npx shadow-cljs release browser server
  '';

  # Create deployable export directory
  export = mkApp "festival-export" ''
    export NODE_ENV=production
    rm -rf export
    mkdir export
    rm -rf .shadow-cljs
    rm -rf public/compiled/*
    clj -X:gen-directus-links
    clj -X:process-reservoir
    bash scripts/resize_images.sh reservoir/imgs/*.JPG
    npx vite build
    cp -r node_modules export/node_modules
    cp -r resources export/resources
    cp package.json export/
    cp package-lock.json export/
    npx shadow-cljs release browser server
    cp -r public export/public
    rm -rf export/public/js/compiled/cljs-runtime
    cp server/server.js export/
    cp settings.edn export/
    chmod -R 755 export
  '';

  # Export with staging configuration
  export-stage = mkApp "festival-export-stage" ''
    export MCT_CONFIG_DIRS="../config/prod ../config/stage"
    export NODE_ENV=production
    rm -rf export
    mkdir export
    rm -rf .shadow-cljs
    rm -rf public/compiled/*
    clj -X:gen-directus-links
    clj -X:process-reservoir
    bash scripts/resize_images.sh reservoir/imgs/*.JPG
    npx vite build
    cp -r node_modules export/node_modules
    cp -r resources export/resources
    cp package.json export/
    cp package-lock.json export/
    npx shadow-cljs release browser server
    cp -r public export/public
    rm -rf export/public/js/compiled/cljs-runtime
    cp server/server.js export/
    cp settings.edn export/
    chmod -R 755 export
  '';

  # Zip the export directory for upload
  zip = mkApp "festival-zip" ''
    cd export
    zip -r ../upload.zip ./*
  '';

  # Run tests (expects MariaDB already running, e.g. via `nix run .#dev`)
  test = mkApp "festival-test" ''
    export CHROME_BINARY_PATH=${pkgs.chromium}/bin/chromium
    npx shadow-cljs release server browser
    clj -M:test -m cognitect.test-runner
  '';

  # Initialize local dev database and directus user
  init-db = mkApp "festival-init-db" ''
    export MYSQL_UNIX_PORT="$PWD/.nix-shell/mysql/mysql.sock"
    bash scripts/init_db.sh
  '';

  # Initialize local Postgres dev database and directus role.
  # Wrapped in `nix develop` so PGHOST/PGPORT/PGUSER/PGDATABASE from the
  # pgEnvHook in flake.nix are in scope — this keeps PGPORT as the single
  # source of truth for the port across dev-infra.
  init-pg-db = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "festival-init-pg-db" ''
      exec nix develop .#default --command bash scripts/init_pg_db.sh
    '';
  };

  # Rebuild local Postgres Directus DB from the MariaDB prod clone via pgloader.
  # Wrapped in `nix develop` so PGHOST/PGPORT/... from pgEnvHook are in scope.
  pg-migrate = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "festival-pg-migrate" ''
      exec nix develop .#default --command bash scripts/pg_migrate.sh
    '';
  };

  # One-time: walk the legacy v9 MariaDB dump (violina-petrychenko.de) through
  # Directus v9 → v10 → v11 schema migrations, then pgloader-pump into local PG.
  bootstrap-legacy = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "festival-bootstrap-legacy" ''
      exec nix develop .#default --command bash scripts/bootstrap_legacy_db.sh "$@"
    '';
  };

  # Repeatable: refresh content rows + uploads from prod into local PG.
  # Assumes bootstrap-legacy has already populated the schema.
  refresh-legacy = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "festival-refresh-legacy" ''
      exec nix develop .#default --command bash scripts/refresh_legacy_content.sh "$@"
    '';
  };

  # Clone production database to local dev
  clone-prod-db = mkApp "festival-clone-prod-db" ''
    export MYSQL_UNIX_PORT="$PWD/.nix-shell/mysql/mysql.sock"
    bash scripts/clone_production_db.sh
  '';

  # Pull uploaded files from production
  pull-files = mkApp "festival-pull-files" ''
    bash scripts/pull_files.sh
  '';

  # Generate Directus presentation-links from route metadata
  gen-directus-links = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "festival-gen-directus-links";
      text = ''
        clj -X:gen-directus-links
        jq . schema/snapshot.json > schema/snapshot.json.tmp && mv schema/snapshot.json.tmp schema/snapshot.json
      '';
      runtimeInputs = commonPackages ++ [ pkgs.jq ];
    };
  };

  # Export Directus schema snapshot (requires running MariaDB + Directus)
  schema-export = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "festival-schema-export";
      text = ''
        mkdir -p schema
        cd directus && npx directus schema snapshot --format json ../schema/snapshot.json
        cd ..
        jq . schema/snapshot.json > schema/snapshot.json.tmp && mv schema/snapshot.json.tmp schema/snapshot.json
        clj -X:gen-directus-links
        jq . schema/snapshot.json > schema/snapshot.json.tmp && mv schema/snapshot.json.tmp schema/snapshot.json
        echo "Schema exported and presentation-links updated."
      '';
      runtimeInputs = commonPackages ++ [ pkgs.jq ];
    };
  };

  # Apply schema snapshot to current Directus instance
  schema-apply = mkApp "festival-schema-apply" ''
    cd directus && npx directus schema apply --yes ../schema/snapshot.json
  '';

  # Upgrade psite libs to latest main and prefetch deps
  upgrade-psite = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "festival-upgrade-psite";
      runtimeInputs = commonPackages ++ [ pkgs.git ];
      text = ''
        PSITE_DIR="$(cd ../../psite && pwd)"

        if [ ! -d "$PSITE_DIR/.git" ]; then
          echo "Error: psite repo not found at $PSITE_DIR" >&2
          exit 1
        fi

        echo "--- Pulling psite ---"
        git -C "$PSITE_DIR" pull

        NEW_SHA=$(git -C "$PSITE_DIR" rev-parse HEAD)
        OLD_SHA=$(grep -oE 'euporos/psite\.git" :git/sha "[0-9a-f]+' deps.edn \
                  | grep -oE '[0-9a-f]{40}' | head -1)

        if [ -z "$OLD_SHA" ]; then
          echo "Error: could not find current psite SHA in deps.edn" >&2
          exit 1
        fi

        if [ "$OLD_SHA" = "$NEW_SHA" ]; then
          echo "Already at latest: ''${NEW_SHA:0:12}"
          exit 0
        fi

        sed -i "s/$OLD_SHA/$NEW_SHA/g" deps.edn
        echo "Updated psite: ''${OLD_SHA:0:12} → ''${NEW_SHA:0:12}"

        echo "--- Prefetching deps ---"
        clj -P
      '';
    };
  };

  # Deploy to production from dev machine.
  # Server checkout lives at festival_pg/ (the directory name was kept after the
  # MariaDB→PG migration); systemd units are festival / festival-directus on :7002/:7003.
  deploy-prod = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "festival-deploy-prod";
      text = ''
        SERVER="phylax@netcup-vps-2-arm"
        echo "=== Deploying to production ==="
        DEPLOYED_SHA=$(ssh "$SERVER" '
          set -euo pipefail
          cd /home/phylax/projects/libs && git pull >&2
          cd /home/phylax/projects/festival_pg/app
          PREV_HEAD=$(git rev-parse HEAD)
          git fetch >&2 && git reset --hard origin/main >&2
          bash scripts/redeploy.sh "$PREV_HEAD" >&2
          git rev-parse HEAD
        ')
        echo "=== Tagging deployed commit $DEPLOYED_SHA ==="
        TAG="deployed-$(date -u +%Y%m%dT%H%M%SZ)"
        git tag "$TAG" "$DEPLOYED_SHA"
        git push origin "$TAG"
        echo "=== Done ($TAG) ==="
      '';
      runtimeInputs = [ pkgs.openssh pkgs.git pkgs.coreutils ];
    };
  };

}
