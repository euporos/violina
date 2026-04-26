#!/usr/bin/env bash
set -e

# PGHOST, PGPORT, PGUSER come from the devShell pgEnvHook in flake.nix.
# We connect to the 'postgres' maintenance DB because PGDATABASE ('directus')
# may not exist yet on a cold run.

psql -d postgres <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'directus') THEN
    CREATE ROLE directus LOGIN PASSWORD 'password';
  END IF;
END
\$\$;

SELECT 'CREATE DATABASE directus OWNER directus'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'directus')\gexec
SQL

echo "Postgres database 'directus' and role 'directus' ready on port $PGPORT."
