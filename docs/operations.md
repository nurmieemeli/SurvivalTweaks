# Operations

## Data safety

Profiles load before they become available to commands. A profile holds the
player's homes — each with its icon, description, favorite state, category,
arrival style, ordering, and a UUID-first world reference — alongside
preferences, one-time onboarding progress, last-seen and cached playtime
metadata, UUID-based mail blocks, and a capped notification inbox.
The inbox retains up to 80 entries. New entries can replace old notifications,
while saved mail stays until the player deletes it or an operator purges it.
When all entries are mail, new deliveries are refused and the sender is told.
Lock ownership transfers notify the recipient. An administrator's transfer
also alerts the original owner; an owner's own transfer skips that admin alert.

Saves go through ordered, coalescing background writers into one transactional
SQL store. Profiles, homes and shares, preferences, notifications, locks,
death markers, and first-join spawn state are updated as complete aggregates:
either the entire change commits or none of it does. Bukkit objects and mutable
collections never cross the asynchronous persistence boundary.

With `storage.backend: auto`, a blank `storage.remote.type` selects SQLite.
`storage.sqlite.file` names the database in the plugin data directory and
defaults to `survivaltweaks.db`. SQLite uses WAL journaling and full synchronous
durability, with a single pooled connection. PostgreSQL and MySQL use a small
bounded HikariCP pool. The release JAR includes all three JDBC drivers.

Remote connections have finite connect, socket, and statement timeouts plus
TCP keepalives, so a dead network cannot leave a persistence worker blocked
forever. Failed asynchronous saves remain queued and retry automatically with
bounded backoff; a newer aggregate safely supersedes an older pending retry.
Each remote endpoint also holds a database-session singleton lock for the
plugin lifetime. Starting a copied server directory against the same endpoint
is rejected before gameplay services can write.

On the first SQL startup, existing `userdata/*.yml`,
`locked-containers.yml`, `death-markers.yml`, and
`new-player-spawns.yml` are parsed and imported into an empty database before
the storage identity is pinned. SurvivalTweaks verifies logical counts and a
deterministic SHA-256 before accepting the import. It keeps the YAML originals
for manual recovery. Once pinned, the database never imports them again.

The active engine, endpoint fingerprint, and random server-instance UUID are
pinned in `storage-state.yml`. Changing the backend or remote endpoint
requires a staged migration. SQLite pins both the filename and database identity.
When moving the plugin data directory, keep that filename. Installations with an
older absolute-path fingerprint upgrade it on successful startup at the original
path. Use a verified backup restore to switch SQLite files. Startup rejects a
missing or unrecognized pinned database. A remote outage
also fails startup: SurvivalTweaks never silently falls back to SQLite, because
doing so could create two divergent copies of player data.

Idle teleport, death-guide, and sleep-vote tasks stop themselves. Player-list
and action-bar updates are sent when visible state changes. Profile,
death-marker, spawn-pool, reload, and backup disk work runs off the server
thread. Shutdown drains pending writes.

## Item duplication

`teleport.cancel-on-inventory-open` is enabled by default. Home teleports and
player teleport requests are blocked while a container is open. Opening one
during warm-up cancels the teleport. The plugin tracks inventory events and
checks the live view when the teleport starts and again before it moves the
player. The guard allows the player's own inventory and SurvivalTweaks menus.
Keep Paper current for fixes to server-side duplication bugs.

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
   password, schema, and TLS setting. Set `storage.remote.password` directly in
   `config.yml` because server commands can appear in logs. PostgreSQL defaults to the dedicated
   `survivaltweaks` schema and `postgresql-ssl-mode: verify-full`, which validates
   the certificate chain and hostname. Existing schema-v2 installations migrate
   to the `public` schema and retain their old `require`/`disable` behavior so
   current tables remain visible.
3. Reload is only a validator; it does not switch the live store. Run
   `/survivaltweaks storage test <backend>`. Testing and migration both read
   the configuration currently saved on disk, so destination edits do not
   require a reload. Testing validates the destination settings; migration
   validates the full configuration and both endpoints before staging.
   PostgreSQL/MySQL testing ignores the SQLite filename; SQLite testing ignores
   remote-only options while keeping its connection timeout validation.
   Databases with unsupported schema versions are rejected before initialization
   changes their schema or SQLite journal mode.
4. Run `/survivaltweaks storage migrate <backend>`. This records a migration
   plan and updates `storage.backend`, preserving edits already saved in
   `config.yml`. If that file changes during staging, publication is rejected;
   retry the command using the updated configuration. The data moves on restart. If shutdown
   interrupts staging before the config is saved, startup keeps the original
   database and discards the incomplete request.
5. Restart Paper. Before services start, SurvivalTweaks opens both endpoints,
   requires an empty destination, copies every aggregate in a transaction,
   compares counts and SHA-256, runs integrity checks, and only then changes
   the pinned backend.

If any connection, copy, checksum, or integrity check fails, startup stops and
the source database remains authoritative. A partially written destination is
cleaned so the same staged migration can be retried after fixing the cause.
Do not manually copy the SQLite file while Paper is running; use
`storage export` for a closed, verified portable snapshot.

To switch between PostgreSQL and MySQL, migrate to SQLite first and start Paper
on SQLite. Then update the remote endpoint settings and stage the second
migration.

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
ZIP archives are capped at 512 MiB uncompressed, with a 16 MiB limit per YAML
file. A config reload can proceed when a backup exceeds those limits, and the
server log records the skipped backup.
Archives contain configuration, both language catalogs, the pinned storage
identity, the configured SQLite database, and preserved legacy YAML files. SQLite
writes are paused briefly and its WAL is checkpointed while the database enters
the ZIP, so the archived file is self-contained. A startup backup recovers any
unfinished SQLite rollback journal before capturing the database. Remote database contents should
also be backed up using the database provider's native backup tooling. In
addition, when PostgreSQL or MySQL is active, SurvivalTweaks automatically
writes a portable SQLite copy to `storage-exports/automatic/`. The default
schedule starts five minutes after startup, repeats every 24 hours, and retains
the newest seven exports. Every copy is reopened and compared with the source
record counts and deterministic checksum before it is accepted. The schedule
and retention can be changed under `storage.portable-exports` and applied with
a validated reload. Manual `storage export` files remain separate and are not
removed by automatic retention. ZIP safety backups keep the newest ten
archives, reserving one slot for the most recent restorable archive when
the latest snapshots cannot be restored. Rotation pauses when it cannot
read an archive it needs to verify, so a temporary file lock cannot cost
the last recovery point. Archives with pending storage migrations cannot be
staged and do not count as recovery points. Verification rejects individual YAML
files over 16 MiB and stops parsing documents that exceed 250,000 parser events.

| Command | Effect |
| --- | --- |
| `/survivaltweaks backup list` | List retained archives |
| `/survivaltweaks backup create` | Create one manually |
| `/survivaltweaks backup verify <file>` | Validate the archive and its local SQLite database, if present, and report its SHA-256 |
| `/survivaltweaks backup restore <file> [confirm]` | Stage a restore for the next startup |

### Restoring

Stage the restore from the local console and restart Paper to apply it.

1. Enable maintenance mode and wait until no players remain online.
2. Issue the final confirmation from the local server console.
3. The chosen archive is copied to a protected pending file and Paper shuts down.
4. On the next startup the plugin re-verifies the staged archive, including any
   SQLite database, language catalogs, and legacy YAML it would import. Before replacing files,
   it creates a fresh `pre-restore` backup. Restore replaces UUID-named legacy
   profile files under `userdata` and leaves unrelated files in place. A full SQLite restore requires an archived
   database or legacy data with at least one importable record. It keeps the original files until the plugin
   finishes starting with the restored data. If the staged archive is
   temporarily unreadable, startup uses the existing data and leaves the
   archive staged for the next restart. On a rejected restore it preserves the
   original files and logs where it saved the rejected archive. A failure
   late in startup may require another server restart. Use the database
   provider's tools to verify or restore remote database contents.

If the data exceeds the ZIP limits, SQLite has write-ahead log or rollback files,
or the restore will replace a second SQLite database, the pre-restore backup is a
verified file copy under the backups directory. It includes the current and
destination databases and their sidecars. The storage pin identifies the current
SQLite file if the live config is unusable. SQLite files are copied together before
SQLite opens them. The backup location appears in the
server log. Keep it until you verify the restored data. This directory is
retained separately from the automatically rotated ZIP archives.

## Diagnostics

`/survivaltweaks doctor` verifies every archive and scans the active SQL store
asynchronously for failed integrity checks, unresolved worlds, expired markers,
and other operational problems. Its worker is
cancelled and joined during shutdown so a late report cannot outlive the plugin
classloader.

`/survivaltweaks storage status` also reports the combined depth, active
workers, and oldest age of the plugin's coalesced persistence queues, plus the
last and next automatic portable-export times. Operators with
`survivaltweaks.health-notify` receive a compact warning after joining when the
database is unavailable or slow, its connection pool has waiters, writes are
stalled or failing, an automatic export has failed or become overdue, or the
newest backup ZIP is missing or over 48 hours old. The join notice checks ZIP
age. Run `/survivaltweaks doctor` to verify that an archive can restore.

### Database maintenance

Run `/survivaltweaks storage maintenance preview` at any time for a read-only
count of expired death markers and structurally invalid rows. Cleanup requires
maintenance mode and an empty server, and its final confirmation should be
issued from the console:

1. Enable maintenance mode and let all players disconnect.
2. Run `/survivaltweaks storage maintenance run confirm`.
3. Preserve the reported safety-export filename and SHA-256.
4. Restart Paper before reopening the server.

The command creates and verifies a portable SQLite safety export before any
deletion. Cleanup then runs under the database snapshot write lock in one SQL
transaction, checks that no targeted rows remain before committing, and runs a
full schema, integrity, relationship, and snapshot verification afterwards.

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

New players can be placed at unique, pre-generated Overworld locations. The SQL
store records prepared and assigned destinations with the world UUID and name.

A location is reserved persistently before teleporting, completed after a
successful placement, and retained afterwards so future selections respect the
configured minimum separation. Pending placement is retried when the player
reconnects. Candidate chunks generate asynchronously and are held loaded with
plugin chunk tickets until assigned.

If the pool is empty on first join, the player starts at Paper's normal spawn
and waits for a prepared location. The plugin remembers their place in the
pool even if they disconnect before the first reservation.

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
