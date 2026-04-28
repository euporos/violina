# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Website for the pianist Violina Petrychenko. Full-stack ClojureScript on Node.js via the [Macchiato](https://macchiato-framework.github.io/) framework, with a Directus headless CMS for content.

## First-time dev setup

Secrets are not committed. After cloning:

```bash
cp directus/.env.example directus/.env  # fill in DB_PASSWORD, KEY, SECRET
```

`settings.edn` uses `#psite/secret "name"` tags to mark required secret positions. The parent `../settings.edn` (outside this repo, untracked) must supply values at the matching key paths: `:admin-email`, `:admin-password`, `:email-transporter {:auth {:user ... :pass ...}}`, `:db-config {:password ...}`. The app refuses to start if any remain unresolved — the error names exactly which tags are missing.

**NixOS hosts:** Directus depends transitively on `sharp`, which ships unpatched prebuilt binaries (linked against `/lib64/ld-linux-x86-64.so.2`). The `flake.nix` does not set this up — it relies on `programs.nix-ld.enable = true` being set on the host. Without it, `npx directus start` fails to load `libstdc++.so.6` at runtime even though `nix run .#dev` boots fine.

## Development Commands

All commands use Nix flake apps (the Makefile has been removed):

```bash
# Initial setup
nix run .#setup               # npm install + create directories

# Development — launches all processes (MariaDB, shadow-cljs, statics watcher, app server, Directus) via process-compose
nix run .#dev

# Building
nix run .#build               # full production build (styles + webpack + shadow-cljs)
nix run .#export              # create deployable export/ directory
nix run .#export-stage        # export with staging config

# Testing
nix run .#test                # clj -M:test -m cognitect.test-runner

# Assets
nix run .#process-reservoir   # process/resize images from reservoir/

# Database
nix run .#init-db             # initialize local dev database and Directus user
nix run .#pull-db-from-prod   # pull production database to local dev

# Directus schema
nix run .#schema-export       # export Directus schema snapshot to schema/snapshot.json
nix run .#schema-apply        # apply schema snapshot to current Directus instance

# Deployment
nix run .#deploy-prod         # deploy to production (pulls main on server, runs redeploy.sh)
```

## Architecture

### Stack
- **Backend:** ClojureScript on Node.js via [Macchiato](https://macchiato-framework.github.io/) framework
- **Frontend:** ClojureScript with [Re-frame](https://github.com/day8/re-frame) state management
- **Routing:** [Reitit](https://github.com/metosin/reitit) with Malli coercion
- **Database:** MySQL (MariaDB 10.6) via HoneySQL query builder
- **CMS:** Directus 11.x (REST API for content/translations)
- **Templating:** Hiccup/Hiccups (Clojure HTML DSL)
- **Styling:** Garden (CSS-in-Clojure) + SCSS + Bulma 0.9.4
- **State:** Mount for lifecycle (defstate), Re-frame for browser state
- **Build:** shadow-cljs (two targets: `:browser` and `:server`) + Webpack (SCSS)

### Source Layout

```
src/
  server/
    serving/        # HTTP server, router, middleware (entry: serving.core/serve)
    seiten/         # Page handlers: home, concerts, musician, admin, page
    api/            # API endpoints: book, ical, qr, proxy
    db/             # Database setup (mount), HoneySQL queries, schema defs
    directus/       # Directus CMS data fetching
    mail/           # Email via nodemailer
    config/         # App configuration (reads settings.edn)
    localization/   # i18n helpers
  browser/
    app/            # Browser modules: core, reframe, modal, admin, obfuscate, book
  common/
    comp/           # Shared UI components and i18n snippets (defsnips macro)
    directus/       # Directus API integration (fetchers)
    style/          # Garden CSS definitions
    processing/     # Build-time asset processing
  dschema/
    dschema/
      core.clj      # defschema macro — compile-time schema validation
      views.clj     # HoneySQL translation view generation
schema/
  snapshot.json     # Directus schema snapshot (exported via nix run .#schema-export)
scripts/
  init_db.sh        # Initialize local MariaDB + Directus admin user
  pull_db_from_prod.sh    # Pull prod DB to local dev
  redeploy.sh       # Production redeployment script
  upgrade_directus.sh     # Directus version upgrade helper
  pull_files.sh     # Pull uploaded files from production
```

### Key Patterns

**URL structure:** `/:locale/...` where locale is one of `:de`, `:en`, `:uk`. Locale fallback order is `[:de :en :uk]`.

**Server handlers** use the `def-go-handler` macro with `go-try` for async operations:
```clojure
(def-go-handler handler [req]
  ;; async DB/Directus calls, returns hiccup HTML response
  )
```

**Routing** is split between `seiten/routes.cljs` (pages) and `api/routes.cljs` (API endpoints), composed in `serving/routes.cljs`.

**Database queries** are composed with HoneySQL in `db/queries.cljs`, using compile-time validated symbols from `db/schema` (e.g., `s/concerts-id` for `:concerts.id`). Translated collections query `_v` views (e.g., `concerts_v`) with `db/localized` for locale-aware COALESCE fallback. Views are generated at compile time by `dschema.views` and created on server startup in `db/setup.cljs`.

**Schema system (dschema):** The `defschema` macro in `src/dschema/dschema/core.clj` reads `schema/snapshot.json` at shadow-cljs compile time and emits into `db.schema`:
- A `def` per collection (e.g., `(def concerts :concerts)`)
- A `def` per field (e.g., `(def concerts-id :concerts.id)`)
- Translation fields appear on the parent collection (e.g., `concerts-title` for a field from `concerts_translations`)
- View name defs for translated collections (e.g., `(def concerts_v :concerts_v)`)
- `view-defs` — HoneySQL maps executed at server startup to CREATE OR REPLACE VIEW
- `schema-meta` — full schema metadata for downstream tooling
- `as` helper for HoneySQL SELECT aliasing

**i18n strings** use the `defsnips` macro in `comp/snippets.cljs` with per-language variants.

**Configuration** lives in `settings.edn` (merged with parent `../settings.edn`). Keys listed in `:share-with-frontend` are available browser-side.

### Build Targets (shadow-cljs.edn)

- `:server` -> `server/server.js` (Node.js script, entry: `serving.core/serve`)
- `:browser` -> `public/compiled/js/` (modules: app, with entries for book, admin, modal, obfuscate)

### Local Libraries (monorepo)

Dependencies in `../../libs/`:
- `putils` - utility macros
- `psite` - core framework (middleware, MySQL wrapper, async helpers)
- `tdstuff` - additional utilities
- `directus` - Directus integration
- `bulma` - Bulma CSS components
- `npm_wrappers` - npm package wrappers
- `ptooling` - build-time processing tools

### Nix Flake Structure

- `flake.nix` — devShells (`default`, `prod`, `deploy`) and apps (imported from `apps.nix`)
- `apps.nix` — all `nix run .#<name>` commands (replaces Makefile)
- `process-compose.yaml` — dev process orchestration (MariaDB, shadow-cljs, watch-statics, app-server, Directus)
- Node.js 22, MariaDB 10.6, JDK 17 provided via Nix

### Configuration (deps.edn aliases)

- `:watch-styles` - watch & recompile Garden CSS
- `:watch-statics` - watch styles + SCSS + reservoir assets (used by process-compose)
- `:compile-styles` - one-time Garden CSS compilation (with minification)
- `:process-reservoir` - process raw images
- `:test` - test runner with cuic, next.jdbc
