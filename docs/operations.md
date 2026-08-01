# Operations

Operator reference for SurvivalTweaks: how data is stored and protected, how
backups and restores work, and how to tune the first-join spawn pool.

## Data safety

Profiles load before they become available to commands. A profile holds the
player's homes — each with its icon, description, favorite state, category,
arrival style, ordering, and a UUID-first world reference — alongside
preferences, one-time onboarding progress, last-seen and cached playtime
metadata, UUID-based mail blocks, and a capped notification inbox.

Saves go through ordered, coalescing background writers into one transactional
SQL store. Profiles, homes and shares, preferences, notifications, locks,
death markers, and first-join spawn state are updated as complete aggregates:
either the entire change commits or none of it does. Bukkit objects and mutable
collections never cross the asynchronous persistence boundary.

SQLite is used automatically when `storage.remote.type` is blank. It keeps
`survivaltweaks.db` in the plugin data directory, uses WAL journaling and full
synchronous durability, and serializes writes through a single pooled
connection. PostgreSQL and MySQL use a small bounded HikariCP pool. All three
JDBC drivers are included in the release JAR.

Remote connections have finite connect, socket, and statement timeouts plus
TCP keepalives, so a dead network cannot leave a persistence worker blocked
forever. Failed asynchronous saves remain queued and retry automatically with
bounded backoff; a newer aggregate safely supersedes an older pending retry.
Each remote endpoint also holds a database-session singleton lock for the
plugin lifetime. Starting a copied server directory against the same endpoint
is rejected before gameplay services can write.

On the first SQL startup, existing `userdata/*.yml`,
`locked-containers.yml`, `death-markers.yml`, and
`new-player-spawns.yml` are parsed and imported only if the SQL database is
empty. SurvivalTweaks verifies logical counts and a deterministic SHA-256
before accepting the import. The YAML originals remain untouched as a manual
rollback source.

The active engine, endpoint fingerprint, and random server-instance UUID are
pinned in `storage-state.yml`. Changing `storage.backend`, the SQLite filename,
or a remote endpoint (including the PostgreSQL schema) by hand is rejected
after initialization. A remote outage
also fails startup: SurvivalTweaks never silently falls back to SQLite, because
doing so could create two divergent copies of player data.

Runtime work is deliberately event-driven. Idle teleport, death-guide, and
sleep-vote tasks stop themselves, and player-list and action-bar updates are
sent only when visible state changes. Profile, death-marker, spawn-pool,
reload, and backup disk work runs off the server thread, with immutable
snapshots and shutdown drains preserving durability.

## Item duplication

Carrying an open container through a teleport is a known duplication technique:
a modified client can keep a shulker box or chest view open while running a
teleport command, leaving its contents recorded in both the container and the
player inventory. `teleport.cancel-on-inventory-open` closes that route and is
enabled by default. Every delayed teleport SurvivalTweaks performs — homes and
player-to-player requests — refuses to start while a container is open, and a
container opened during a warm-up cancels the teleport rather than completing
it.

Open containers are tracked from inventory events and confirmed against the
player's live inventory view at teleport start and cutover. A player's own
inventory never counts. SurvivalTweaks' own menus are exempt because every one
of them cancels its clicks and drags and therefore holds nothing a player can
take.

This addresses the duplication routes a plugin can control. Duplication bugs in
the server itself are fixed in Paper, so running a current Paper build remains
the primary defence.

## Database administration

| Command | Effect |
| --- | --- |
| `/survivaltweaks storage status` | Show backend, schema, latency, and pool use |
| `/survivaltweaks storage verify` | Run engine integrity and orphan checks |
| `/survivaltweaks storage export` | Create a verified, portable SQLite snapshot |
| `/survivaltweaks storage test <backend>` | Connect to and verify a destination without switching |
| `/survivaltweaks storage migrate <backend>` | Stage a verified migration for the next restart |

### Moving SQLite to PostgreSQL or MySQL

1. Create an empty database and a dedicated user with permission to create,
   read, update, and delete its tables.
2. Keep `storage.backend: sqlite`, then set `storage.remote.type` to either
   `postgresql` or `mysql` and fill in the host, port, database, username,
   password, schema, and TLS setting. PostgreSQL defaults to the dedicated
   `survivaltweaks` schema and `postgresql-ssl-mode: verify-full`, which validates
   the certificate chain and hostname. Existing schema-v2 installations migrate
   to the `public` schema and retain their old `require`/`disable` behavior so
   current tables remain visible.
3. Reload is only a validator; it does not switch the live store. Run
   `/survivaltweaks storage test <backend>`.
4. Run `/survivaltweaks storage migrate <backend>`. This records a migration
   plan and updates `storage.backend`, but does not move live data.
5. Restart Paper. Before services start, SurvivalTweaks opens both endpoints,
   requires an empty destination, copies every aggregate in a transaction,
   compares counts and SHA-256, runs integrity checks, and only then changes
   the pinned backend.

If any connection, copy, checksum, or integrity check fails, startup stops and
the source database remains authoritative. A partially written destination is
cleaned so the same staged migration can be retried after fixing the cause.
Do not manually copy the SQLite file while Paper is running; use
`storage export` for a closed, verified portable snapshot.

The same command can stage a move from the configured remote backend back to
SQLite. Direct PostgreSQL-to-MySQL moves are intentionally not implicit:
migrate to SQLite first, update the one remote endpoint configuration, start
successfully on SQLite, and then stage the second migration.

After login, players receive one compact session line only when something needs
attention. It combines unread notifications, pending teleport requests, an
active recovery marker, maintenance mode, and a scheduled restart, with one
localized shortcut to the player hub. It replaces separate join-time notices
for those states and does not appear for an empty session.

## Adaptive performance

The `performance` section controls a three-level governor driven by Paper's
average MSPT. Crossing `reduced-mspt` or `critical-mspt` immediately reduces
ambient/death-guide update frequency, particle density, and the amount of
batchable work admitted per tick. Recovery requires MSPT to remain below
`recovery-mspt` for `recovery-seconds`, stepping down one level at a time to
avoid oscillation.

`work-budget-per-tick` is shared by tree felling, fast leaf decay, first-join
spawn preparation, atmosphere, and death-guide work. Exhausted jobs resume on a
later tick; they are not discarded. Teleport safety, lock enforcement, data
persistence, and other correctness-sensitive work bypass this budget.

Each batchable workload receives a fair per-tick lane, preventing one large
canopy or tree from consuming work reserved for spawn preparation or cosmetic
guidance. Tree jobs additionally rotate one block at a time between players.

`/survivaltweaks doctor` reports an active governor reduction and any recurring
subsystem failure seen within the previous ten minutes. Repeating cosmetic and
status tasks catch, rate-limit, and record their own exceptions, allowing the
next scheduled run to proceed.

`/survivaltweaks performance` provides a live operator snapshot: governor level,
MSPT, recovery progress, cosmetic scaling, used and denied lane work, deferred
tree/leaf/spawn queues, and recent isolated failures. Governor transitions are
logged once when the level changes, with the triggering MSPT and reason.

## Backups

Before startup loads or migrates data, and before every configuration reload,
the plugin writes an atomic ZIP snapshot into `plugins/SurvivalTweaks/backups/`.
Archives contain configuration, both language catalogs, the pinned storage
identity, the default SQLite database, and preserved legacy YAML files. SQLite
writes are paused briefly and its WAL is checkpointed while the database enters
the ZIP, so the archived file is self-contained. Remote database contents should
also be backed up using the database provider's native backup tooling. In
addition, when PostgreSQL or MySQL is active, SurvivalTweaks automatically
writes a portable SQLite copy to `storage-exports/automatic/`. The default
schedule starts five minutes after startup, repeats every 24 hours, and retains
the newest seven exports. Every copy is reopened and compared with the source
record counts and deterministic checksum before it is accepted. The schedule
and retention can be changed under `storage.portable-exports` and applied with
a validated reload. Manual `storage export` files remain separate and are not
removed by automatic retention. The newest ten ZIP safety archives are
retained.

| Command | Effect |
| --- | --- |
| `/survivaltweaks backup list` | List retained archives |
| `/survivaltweaks backup create` | Create one manually |
| `/survivaltweaks backup verify <file>` | Validate an archive and report its SHA-256 |
| `/survivaltweaks backup restore <file> [confirm]` | Stage a restore for the next startup |

### Restoring

Restoration is deliberately staged and cannot be completed in one step:

1. Enable maintenance mode and wait until no players remain online.
2. Issue the final confirmation from the local server console — not in-game.
3. The chosen archive is copied to a protected pending file and Paper shuts down.
4. On the next startup the plugin creates a fresh `pre-restore` backup,
   re-verifies the staged archive, swaps only plugin-managed files with rollback
   protection, and applies current defaults before any player or world data
   loads.

## Diagnostics

`/survivaltweaks doctor` verifies every archive and scans the active SQL store
asynchronously for failed integrity checks, unresolved worlds, expired markers,
and other operational problems. Its worker is
cancelled and joined during shutdown so a late report cannot outlive the plugin
classloader.

## Configuration migrations and release notices

`config-version` identifies the configuration schema. Startup first creates its
normal safety backup, then applies ordered migrations for older schemas. Known
obsolete command-disabling and physical recovery-compass settings are removed;
unrelated server customizations are preserved. A successful migration writes
`config-migration-report.txt` in the plugin data folder and logs its schema
range. A configuration created by a newer unsupported plugin build is rejected
to prevent a destructive downgrade. Reloads require the current schema, so an
outdated file is migrated by restarting once.

The schema-v3 migration adds PostgreSQL schema, TLS-mode, socket-timeout, and
query-timeout settings without changing an existing database location.
The schema-v4 migration enables scheduled, verified portable exports for remote
databases with conservative schedule and retention defaults.

The `updates` section controls the administrator release notice. Players need
`survivaltweaks.update-notify` (operator by default). The GitHub repository is
derived from the website in plugin metadata. The check runs asynchronously
after `join-delay-ticks`, finds the actual downloadable
`SurvivalTweaks-<version>.jar`, compares its filename version with the running
build, uses short network timeouts, and caches the result for
`check-interval-hours`. Network, API, or missing-asset failure never blocks
joining and produces no player-facing error.

## First-join spawn pool

New players can be placed at unique, pre-generated Overworld locations so
terrain generation never interrupts their first moments. Prepared and assigned
destinations are stored in `new-player-spawns.yml` with both world UUID and
name.

A location is reserved persistently before teleporting, completed after a
successful placement, and retained afterwards so future selections respect the
configured minimum separation. Pending placement is retried when the player
reconnects. Candidate chunks generate asynchronously and are held loaded with
plugin chunk tickets until assigned.

Generation runs one candidate at a time, waits `generation-delay-ticks` between
candidates, and pauses whenever the one-minute TPS falls below `minimum-tps`.
The entire landing square must have safe footing, headroom, acceptable height
variation, and an allowed biome. Ocean biomes are blocked by default, and an
empty `allowed-biomes` list accepts any biome not explicitly blocked.

If an assigned destination becomes unsafe or its world disappears before the
first teleport completes, that location is permanently retired and a replacement
is reserved atomically. Players awaiting a replacement stay eligible across
restarts.

| Command | Effect |
| --- | --- |
| `/survivaltweaks spawnpool status` | Pool readiness, assignments, TPS, and generation metrics |
| `/survivaltweaks spawnpool refill` | Request TPS-aware replenishment |
| `/survivaltweaks spawnpool validate` | Revalidate prepared and pending locations asynchronously |
| `/survivaltweaks spawnpool clear-prepared [confirm]` | Clear only unassigned prepared locations |

## Maintenance and restarts

Maintenance mode rejects new non-bypass joins while leaving connected players
undisturbed; `survivaltweaks.maintenance.bypass` allows operators through.

Scheduled restarts accept explicit second, minute, or hour units, show localized
milestone messages and a live boss bar, and block late joins during the final
`maintenance.join-block-seconds` window. At zero, players receive a friendly
localized disconnect, profile writes are flushed, an optional safety backup is
created, and Paper shuts down cleanly. An external process manager should be
responsible for starting the server again.

## Notes

Paper currently marks its dialog API as experimental, so all dialog code is
isolated from command and persistence logic. Native dialogs can be disabled with
`ui.dialogs-enabled`, and every dialog has an equivalent command fallback.

Player chat names open the sender's profile. Known failed command states append
a localized clickable correction or relevant interface shortcut; console
output remains plain. Custom-enchanted item lore records activation conditions,
acquisition sources, and relevant incompatibilities without adding any runtime
item setting.
