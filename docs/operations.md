# Operations

Operator reference for SurvivalTweaks: how data is stored and protected, how
backups and restores work, and how to tune the first-join spawn pool.

## Data safety

Profiles load before they become available to commands. A profile holds the
player's homes — each with its icon, description, favorite state, category,
arrival style, ordering, and a UUID-first world reference — alongside
preferences, one-time onboarding progress, last-seen and cached playtime
metadata, UUID-based mail blocks, and a capped notification inbox.

Saves go through one ordered, coalescing background writer using atomic file
replacement, so Bukkit objects and mutable collections never cross a thread
boundary. Unchanged and intermediate snapshots are skipped. Data is written
after changes, on disconnect, during periodic autosaves, and at shutdown.

The latest death marker per player lives in `death-markers.yml`, recorded with
both world UUID and name so markers survive restarts and expire on schedule.
Container locks persist independently in `locked-containers.yml` using world
UUIDs and block coordinates.

Older schemas — including version 1.0 `userdata/<uuid>.yml` home files — are
detected on load and queued for migration into the current versioned format.

Runtime work is deliberately event-driven. Idle teleport, death-guide, and
sleep-vote tasks stop themselves, and player-list and action-bar updates are
sent only when visible state changes. Profile, death-marker, spawn-pool,
reload, and backup disk work runs off the server thread, with immutable
snapshots and shutdown drains preserving durability.

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
Archives contain configuration, both language catalogs, locks, death markers,
first-join spawn state, and profile YAML files. The newest ten are retained.

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

`/survivaltweaks doctor` verifies every archive and scans the remaining data
asynchronously for invalid schemas, unresolved worlds, overlapping locks, and
other operational problems. Its worker is
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
