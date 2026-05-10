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
    drv = pkgs.writeShellScriptBin "violina-dev" ''
      exec nix develop .#default --command ${pkgs.process-compose}/bin/process-compose up -f process-compose.yaml
    '';
  };

  # Build all Directus extensions
  build-directus-extensions = mkApp "violina-build-directus-extensions" ''
    for ext in directus/extensions/directus-extension-*/; do
      if [ -f "$ext/package.json" ]; then
        echo "Building extension: $ext"
        (cd "$ext" && npx --package=@directus/extensions-sdk directus-extension build)
      fi
    done
  '';

  # Initial project setup
  setup = mkApp "violina-setup" ''
    npm install
    (cd directus && npm install)
    nix run .#build-directus-extensions
    mkdir -p export public public/from_reservoir
  '';

  # Process and resize reservoir images
  process-reservoir = mkApp "violina-process-reservoir" ''
    clj -X:process-reservoir
    bash scripts/resize_images.sh reservoir/imgs/*.JPG
  '';

  # Full production build
  build = mkApp "violina-build" ''
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
  export = mkApp "violina-export" ''
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
  export-stage = mkApp "violina-export-stage" ''
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
  zip = mkApp "violina-zip" ''
    cd export
    zip -r ../upload.zip ./*
  '';

  # Run tests (expects MariaDB already running, e.g. via `nix run .#dev`)
  test = mkApp "violina-test" ''
    export CHROME_BINARY_PATH=${pkgs.chromium}/bin/chromium
    npx shadow-cljs release server browser
    clj -M:test -m cognitect.test-runner
  '';

  # Initialize local dev database and directus user
  init-db = mkApp "violina-init-db" ''
    export MYSQL_UNIX_PORT="$PWD/.nix-shell/mysql/mysql.sock"
    bash scripts/init_db.sh
  '';

  # Initialize local Postgres dev database and directus role.
  # Wrapped in `nix develop` so PGHOST/PGPORT/PGUSER/PGDATABASE from the
  # pgEnvHook in flake.nix are in scope — this keeps PGPORT as the single
  # source of truth for the port across dev-infra.
  init-pg-db = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "violina-init-pg-db" ''
      exec nix develop .#default --command bash scripts/init_pg_db.sh
    '';
  };

  # Rebuild local Postgres Directus DB from the MariaDB prod clone via pgloader.
  # Wrapped in `nix develop` so PGHOST/PGPORT/... from pgEnvHook are in scope.
  pg-migrate = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "violina-pg-migrate" ''
      exec nix develop .#default --command bash scripts/pg_migrate.sh
    '';
  };

  # One-time: walk the legacy v9 MariaDB dump (violina-petrychenko.de) through
  # Directus v9 → v10 → v11 schema migrations, then pgloader-pump into local PG.
  bootstrap-legacy = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "violina-bootstrap-legacy" ''
      exec nix develop .#default --command bash scripts/bootstrap_legacy_db.sh "$@"
    '';
  };

  # Repeatable: refresh content rows + uploads from prod into local PG.
  # Assumes bootstrap-legacy has already populated the schema.
  refresh-legacy = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "violina-refresh-legacy" ''
      exec nix develop .#default --command bash scripts/refresh_legacy_content.sh "$@"
    '';
  };

  # Pull production Postgres directus DB → local devShell Postgres.
  # Wrapped in `nix develop` so PGHOST/PGPORT/... from pgEnvHook are in scope.
  pull-db-from-prod = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "violina-pull-db-from-prod" ''
      exec nix develop .#default --command bash scripts/pull_db_from_prod.sh
    '';
  };

  # Pull uploaded files from production
  pull-files = mkApp "violina-pull-files" ''
    bash scripts/pull_files.sh
  '';

  # Push local Postgres directus DB to production (destructive: replaces prod DB).
  # Wrapped in `nix develop` so PGHOST/PGPORT/... from pgEnvHook resolve locally.
  # Requires NOPASSWD sudo on the server for `systemctl start/stop` of the
  # violina-macchiato{,-directus}.service units — declared in the server flake
  # at ../servers/vps2/netcup-vps-2/configuration.nix (security.sudo.extraConfig).
  push-db-to-prod = flake-utils.lib.mkApp {
    drv = pkgs.writeShellScriptBin "violina-macchiato-push-db-to-prod" ''
      exec nix develop .#default --command bash scripts/push_db_to_prod.sh
    '';
  };

  # Generate Directus presentation-links from route metadata
  gen-directus-links = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "violina-gen-directus-links";
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
      name = "violina-schema-export";
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
  schema-apply = mkApp "violina-schema-apply" ''
    cd directus && npx directus schema apply --yes ../schema/snapshot.json
  '';

  # Upgrade psite libs to latest main and prefetch deps
  upgrade-psite = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "violina-upgrade-psite";
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
  # Server checkout lives at violina_macchiato/app on netcup-vps-2;
  # systemd units are violina-macchiato / violina-macchiato-directus on :7102/:7103.
  deploy-prod = flake-utils.lib.mkApp {
    drv = pkgs.writeShellApplication {
      name = "violina-macchiato-deploy-prod";
      text = ''
        SERVER="phylax@netcup-vps-2-arm"
        echo "=== Checking local main vs origin/main ==="
        git fetch origin main >/dev/null 2>&1 || true
        LOCAL_MAIN=$(git rev-parse main)
        REMOTE_MAIN=$(git rev-parse origin/main 2>/dev/null || echo "")
        if [ "$LOCAL_MAIN" != "$REMOTE_MAIN" ]; then
          echo "Local main ($LOCAL_MAIN) differs from origin/main ($REMOTE_MAIN)."
          read -r -p "Force push main to origin first? [y/N] " ANSWER
          case "$ANSWER" in
            [yY]|[yY][eE][sS])
              echo "=== Force pushing main ==="
              git push --force-with-lease origin main
              ;;
            *)
              echo "Skipping push. Server will deploy whatever is currently on origin/main."
              ;;
          esac
        else
          echo "main is up to date with origin/main."
        fi
        echo "=== Deploying to production ==="
        DEPLOYED_SHA=$(ssh "$SERVER" '
          set -euo pipefail
          cd /home/phylax/projects/libs && git pull >&2
          cd /home/phylax/projects/violina_macchiato/app
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
