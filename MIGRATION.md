# MariaDB → PostgreSQL migration notes

This repo was migrated from MariaDB to PostgreSQL on the `pg` branch (16 commits, merged
into `main` 2026-04-24). The migration produced two intermixed categories of change:

1. **Reusable tooling** that can be copied/adapted into other MySQL-backed projects when
   they get the same treatment.
2. **Baked-in app state** — the *result* of the migration. Each project will produce its
   own equivalents; nothing to copy.

This file is the map.

## 1. Reusable migration tooling

Copy these into the target project and adapt the names/paths:

| Path | What it is | What to change |
| --- | --- | --- |
| `pg_migration/festival.load` | pgloader config (data-only, schema rename) | Rename file; update source DB name, target DB name, the `ALTER SCHEMA ... RENAME` |
| `scripts/init_pg_db.sh` | Idempotent PG role + database bootstrap | Role name, DB name, password source |
| `scripts/pg_migrate.sh` | Full local cutover pipeline: clone prod MariaDB → `directus bootstrap` → `schema apply` → `pgloader` → `TRUNCATE directus_sessions` → restart Directus | Directus paths; the `TRUNCATE directus_sessions` step stays (carried-over sessions cause an admin-UI login loop) |
| `scripts/pg_redeploy.sh` | Server-side redeploy with rollback safety (keep `PREV_HEAD`, `git reset --hard` on failure, stop/start systemd units) | Systemd unit names, `CONFIG_PATH`, service start order |
| `apps.nix` entries `init-pg-db`, `pg-migrate`, `push-pg-to-server`, `deploy-prod-pg` | Thin `nix develop .#default --command ...` wrappers around the scripts above | Server hostname, remote checkout path, branch name |
| `flake.nix` — `postgresql_17` in commonPackages + `pgEnvHook` | PG binaries in shell and `PGHOST`/`PGPORT`/`PGUSER`/`PGDATABASE` exported from one place so every script and every `nix run .#pg-*` sees the same port | PGPORT value; keep the "single source of truth" pattern |
| `process-compose.yaml` — `postgres` process alongside `mariadb`, with readiness probe and `shutdown.signal` | Lets both DBs run in parallel during the cutover window | Drop the `mariadb` process once the target project's cutover is complete |

### Checklist for the next MySQL → PG migration

1. Copy the table above's files into the target repo; rename `festival.load` and
   adjust source/target DB names + schema rename.
2. Adjust `scripts/init_pg_db.sh` (role, DB name, password source) and the four
   `apps.nix` pg-* entries (server hostname, remote path, branch).
3. Adjust `scripts/pg_redeploy.sh` systemd unit names and `CONFIG_PATH`.
4. Swap the target project's DB driver in `deps.edn`
   (`psite-mysql2` → `psite-pg`) and regenerate the Directus schema snapshot
   against the PG target after `directus bootstrap`.
5. Grep the target project for MySQL-isms and replace with PG equivalents.
   Reference commits on this branch:
   - `aca6c74` — `YEAR(x)` → `EXTRACT(YEAR FROM x)`, `CURDATE()` → `CURRENT_DATE`
   - `50d057f`, `de63b1e` — mixed-case identifiers (e.g. `venues.Name`) need
     double-quoting; route through dschema symbols instead of inline keywords
   - Also hunt for: backtick identifiers, `ON DUPLICATE KEY UPDATE` (PG uses
     `ON CONFLICT`), `LIMIT x, y` (PG uses `LIMIT y OFFSET x`)
6. Run `nix run .#init-pg-db` then `nix run .#pg-migrate` locally.
7. `nix run .#push-pg-to-server` for first-time server data seed, then
   `nix run .#deploy-prod-pg` for subsequent code deploys.

## 2. Baked-in app state (not reusable — each project produces its own)

| Path | What changed |
| --- | --- |
| `src/psite-pg/psite_pg/core.cljs` | Inline `node-pg` wrapper (API-mirrors the old `psite-mysql2`); registers type parsers for OIDs 1082 / 1114 / 1184 so `DATE`/`TIMESTAMP`/`TIMESTAMPTZ` come through as Luxon DateTimes |
| `deps.edn` | `psite-mysql2` → `psite-pg` |
| `src/server/db/setup.cljs` | Pool construction via psite-pg; type parsers registered; dropped the mysql2 `:typeCast` shim |
| `src/server/seiten/admin.cljs` | `YEAR()` / `CURDATE()` → PG equivalents |
| `src/server/seiten/concerts.cljs`, `src/server/api/book.cljs`, `src/server/api/ical.cljs` | Mixed-case column identifiers routed via dschema symbols (auto-quoted) instead of inline keywords |
| `src/server/serving/core.cljs` | Minor wiring adjustments |
| `schema/snapshot.json` | Regenerated from PG; `concert_files.directus_files_id` is now `uuid` (was `varchar(36)` under MariaDB) |
| `settings.edn`, `settings_test.edn` | `:db-config` points at PG on `localhost:5435` |
| `directus/.env.example`, `directus/package.json` (+ lockfile) | `DB_CLIENT=pg`, pg driver added |
| `vite.config.js` → `vite.config.mjs` | Rename (unrelated to PG, happened on the branch) |

## 3. One-off artifacts from *this* cutover — don't copy

Uncommitted / untracked items that are specific to this repo's migration event:

- `festival_directus_backup.sql`, `pre_directus_upgrade_20260424_1948.sql` —
  Directus backups taken at the cutover window
- `migrations/` — empty leftover directory
- `.playwright-mcp/`, `screenshots/`, `assessment.org` — review/test artifacts
- `deploy/` — local scratch during deploy prep

## Retrospective gotchas

From the actual cutover (worth re-reading before the next one):

- The Directus schema snapshot is not fully portable across DB vendors — FK column
  types (e.g. `varchar(36)` ↔ `uuid`) need a regen on the target DB after
  `directus bootstrap`.
- `directus schema apply` is interactive by default; use `--yes`.
- Carried-over rows in `directus_sessions` cause an admin-UI redirect loop on first
  login after pgloader. `TRUNCATE directus_sessions` post-pump. (See `pg_migrate.sh`.)
- `nix run .#foo` does not inherit the devShell's `shellHook` exports — wrap the
  script body in `nix develop .#default --command ...` for every pg-* app.
- On NixOS the server has no declarative `ensureDatabases` / `ensureUsers` (those
  break PG major upgrades), so the server's PG role + DB must be created by a
  one-off `psql` run, not by the module.
