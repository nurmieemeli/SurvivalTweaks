# Changelog

## 2.25.0 - 2026-08-09

### Added

- A `default-language` setting that chooses the language for text with no
  player to address: server list pings, pre-login kicks, console output, and
  the lore on items handed out by loot tables and villager trades. Players
  still see their own language everywhere it is known.
- A `storage.remote.mysql-ssl-mode` setting that selects how far MySQL TLS
  validates the server certificate.

### Changed

- MySQL connections validate the server certificate by default on new
  installations. Existing servers keep their previous encrypt-without-verify
  behaviour until the new setting is raised, and the migration report says so.
- Configuration migrates automatically to schema 7; the report lists every
  setting that was added.
- Trust and transfer menus build their candidate list off the server thread,
  and searching for a player resolves the typed name directly instead of
  scanning every player who has ever joined.
- Database verification reads the data once rather than twice, halving what a
  diagnostics run costs on a large database.
- Safe teleport searches outwards from the requested height instead of upwards
  from the bottom of its range, so players land at the nearest safe spot rather
  than up to three blocks below it.

### Fixed

- Persistence flushes wait for every write queued before them, so snapshots,
  exports, and safety backups can no longer be published one revision stale.
- A write lane no longer stops for the rest of the run if its thread is
  interrupted while retrying a failed save.
- Restoring a backup clears the SQLite write-ahead log left behind by an
  unclean shutdown. Pairing one with a restored database risked corruption, or
  silently reinstating the data the restore was meant to undo.
- Pistons can no longer move or destroy a locked container, which left the lock
  pointing at empty space and the container unprotected at its new position.
- The lock status hint honours profile-wide `/trust`, instead of reporting a
  container as denied while opening it actually succeeded.
- `/home invite` and shared-home teleports reject player names that have never
  joined, rather than accepting them against an invented profile, and no longer
  risk stalling the server while resolving a name.
- Statistics for an online player are read on the server thread.
- A portable export is no longer reported as failed when the export itself
  succeeded and only its retention cleanup did not.
- Opening the lock trust or transfer menu no longer resolves a player name once
  per comparison while sorting the roster.
- The configuration get and set commands and the storage migration prompts
  showed a missing-message placeholder instead of their text.
- A `config set` whose value is rejected by validation is taken back out of
  config.yml. The running server already kept its previous settings, but the
  rejected value stayed on disk and would have stopped the plugin from
  enabling at the next restart.
- The administrator usage line lists `config`, which help and tab completion
  already offered.
- Changing the first-join spawn pool while the server is shutting down no
  longer raises out of the join or command handler that triggered it. The
  change is now declined and undone instead.
- First-join spawn preparation no longer stops silently when the per-tick work
  budget is lowered. Its request was larger than one lane's share of a small
  budget, so it was deferred on every tick and never ran: entirely below a
  budget of 36, and under load below 103. A lane that has not run yet in a tick
  may now take an oversized grant when the tick can cover it.

## 2.24.0 - 2026-08-09

### Added

- Profile-wide `/trust` access for all containers owned by a player.
- Persistence coordination that drains pending profile, lock, death-marker,
  and first-join spawn writes before snapshots, backups, and maintenance.

### Changed

- Portable database exports are verified before being published atomically.
- Remote database singleton leases are monitored continuously, and configured
  query timeouts now apply consistently to MySQL and PostgreSQL statements.

### Fixed

- Database maintenance no longer allows cached aggregates to restore purged
  mail or expired death markers.
- Profile-wide trusts are included in storage checksums and record counts, and
  trust preload failures now fail closed instead of silently denying access.
- Players now retain their personal first-join random location as their native
  respawn point, while valid beds and respawn anchors remain respected.

## 2.23.0 - 2026-08-01

### Added

- An architecture and state-ownership map, cross-feature player journey
  contracts, and a complete 2.23 audit report for maintainers.
- Runtime session and join-experience coordinators with lifecycle and ownership
  contract coverage.

### Changed

- Returning-player arrival feedback is coordinated so a rich welcome overview
  suppresses duplicate activity counts while retaining urgent maintenance and
  restart notices.
- Configuration reload now applies the exact validated candidate to every
  runtime consumer.

### Fixed

- Player identity, play-time, and last-seen persistence no longer depend on a
  welcome-screen controller.
- Configuration reload rejects a file changed during validation and restores
  previous runtime settings and configuration if application fails.

## 2.22.0 - 2026-08-01

### Added

- Live persistence telemetry for profile, container-lock, death-marker, and
  first-join spawn writes, including queue depth, active workers, queue age,
  and the latest failure.
- Join-time operator warnings for unavailable or slow storage, exhausted
  connection pools, stalled writes, failed or overdue portable exports, and
  missing or stale safety backups.
- Previewable database maintenance that creates a verified portable safety
  export, removes expired or structurally invalid rows transactionally, and
  verifies the active database afterwards.
- A representative large-data round-trip test covering 2,000 profiles, 1,000
  container locks, and 1,000 death markers.

### Changed

- Storage status now includes persistence queue and automatic portable-export
  state.
- Production deployment guidance now includes explicit preflight, backup,
  verification, and rollback checkpoints for remote storage releases.

## 2.21.0 - 2026-08-01

### Added

- PostgreSQL and MySQL storage with explicit, verified migrations to and from
  SQLite.
- Scheduled, retention-managed portable SQLite exports for remote databases;
  every export is reopened and verified before acceptance.
- PostgreSQL schema and TLS-mode controls, finite network/query timeouts, and
  live integration coverage for PostgreSQL and MySQL.

### Changed

- Logical snapshots now use bulk table reads in one consistent transaction.
- Lock, death-marker, and first-join spawn aggregates now persist with
  key-level deltas instead of unconditional table rewrites.
- Profile scalar rows are updated in place, reducing foreign-key churn.
- Persistence workers retry transient failures and drain safely during
  shutdown.

### Fixed

- Legacy YAML import now fails safely on malformed entries and preserves the
  source files until record counts and checksums have been verified.
- Interrupted database migrations and first-start imports recover without
  silently selecting the wrong source.
- Remote endpoints are protected by a database-session lease so copied server
  instances cannot write to the same store concurrently.
- Teleport and UI lifecycle edge cases uncovered during the storage audit.
