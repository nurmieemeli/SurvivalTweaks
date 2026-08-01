# Changelog

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
