{
  description = "Sounds of Ukraine - Festival Macchiato";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # Shared dependencies (replaces common.nix)
        commonPackages = with pkgs; [
          mariadb_106
          postgresql_17
          pgloader
          imagemagick
          nodejs_22
          jdk17
          clojure
          nodePackages.npm
          sass
          chromium
          bash
        ];

        # Environment variables shared across all shells
        commonEnv = {
          SASS_DIRECTORY = pkgs.sass;
          SCSS_EXECUTABLE = "${pkgs.sass}/bin/scss";
          CHROME_BINARY_PATH = "${pkgs.chromium}/bin/chromium";
        };

        # Tomato-colored prompt (RGB 255,99,71) showing repo_name:relative/path
        promptHook = ''
          __repo_root="$(${pkgs.git}/bin/git rev-parse --show-toplevel 2>/dev/null || ${pkgs.coreutils}/bin/pwd)"
          __repo_name="$(${pkgs.coreutils}/bin/basename "$__repo_root")"
          __relative_path="$(${pkgs.coreutils}/bin/realpath --relative-to="$__repo_root" "$PWD" 2>/dev/null || echo ".")"
          if [ "$__relative_path" = "." ]; then
            export PS1="\[\033[38;2;255;99;71m\]$__repo_name\[\033[0m\] \$ "
          else
            export PS1="\[\033[38;2;255;99;71m\]$__repo_name:$__relative_path\[\033[0m\] \$ "
          fi
          unset __repo_root __repo_name __relative_path
        '';

        # MySQL environment variables (no init, no daemon — process-compose handles that)
        mysqlEnvHook = ''
          export MYSQL_BASEDIR=${pkgs.mariadb_106}
          export MYSQL_HOME=$PWD/.nix-shell/mysql
          export MYSQL_DATADIR=$MYSQL_HOME/data
          export MYSQL_UNIX_PORT=$MYSQL_HOME/mysql.sock
          export MYSQL_PID_FILE=$MYSQL_HOME/mysql.pid
          alias mysql='mysql -u root'
        '';

        # PostgreSQL environment variables. PGPORT is the single source of truth
        # for the port — everything downstream (process-compose, psql, init script)
        # reads it from here.
        pgEnvHook = ''
          export PGHOME=$PWD/.nix-shell/postgres
          export PGDATA=$PGHOME/data
          export PGHOST=$PGHOME
          export PGPORT=5435
          export PGUSER=postgres
          export PGDATABASE=directus
        '';

      in {
        devShells = {
          # Default dev shell — all tools + process-compose, DB env for MySQL + PG
          default = pkgs.mkShell ({
            nativeBuildInputs = commonPackages ++ [ pkgs.process-compose ];
            shellHook = promptHook + mysqlEnvHook + pgEnvHook;
          } // commonEnv);

          # Production shell — NODE_ENV=production, no MySQL
          prod = pkgs.mkShell ({
            nativeBuildInputs = commonPackages;
            NODE_ENV = "production";
            shellHook = promptHook;
          } // commonEnv);

          # Deploy shell — like prod but with zip
          deploy = pkgs.mkShell ({
            nativeBuildInputs = commonPackages ++ [ pkgs.zip ];
            NODE_ENV = "production";
            shellHook = promptHook;
          } // commonEnv);
        };

        apps = import ./apps.nix { inherit pkgs flake-utils commonPackages; };
      }
    );
}
