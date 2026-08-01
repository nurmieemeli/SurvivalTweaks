# Architecture audit for 2.23

This audit reviewed the plugin as a complete system rather than as isolated
features. It covered composition and shutdown, state ownership, player journey
ordering, Bukkit thread boundaries, recurring tasks, configuration reload,
persistence queues, SQL transactions, UI lifecycles, locale resources,
permissions, and release verification.

## Corrections made

### Session state ownership

Player identity, play time, and last-seen state were previously updated by a UI
controller. That made a presentation feature responsible for core persistence
and allowed lifecycle behavior to drift when the UI was disabled or reordered.

`PlayerSessionService` now owns that state. The connection listener starts and
ends sessions, while welcome-back presentation consumes an immutable session
snapshot. A source-level contract test prevents future runtime writers.

### Join experience coordination

Join processing called several otherwise-correct services independently. A
returning player could receive both a rich welcome overview and a compact
summary containing the same mail, request, and death-marker counts.

`JoinExperienceCoordinator` now defines the arrival order and feedback budget.
When the rich overview is scheduled, the compact summary keeps urgent
maintenance and restart state but suppresses duplicated activity.

### Atomic configuration reload

Reload validation produced a valid candidate, but runtime callbacks could read
a separately reloaded plugin configuration. The file could also change between
validation and application, creating a small time-of-check/time-of-use window.

Reload now reads and fingerprints one candidate, validates it completely, and
passes that exact object to every runtime consumer. It rejects a changed file
and rolls consumers back to the previous settings and configuration if apply
fails. Tests cover exact-candidate delivery and rollback.

## Boundaries verified

- Async pre-login performs storage/cache work without touching Bukkit world
  state.
- Async chat and mention paths return presentation work to the server thread.
- Database writes, exports, backups, and release checks do not block the tick
  thread.
- Persistent writers coalesce work, expose queue health, retry transient
  failures, and drain during shutdown.
- Remote database selection does not silently fall back after it has become
  authoritative.
- Teleport safety, lock enforcement, and persistence correctness are never
  degraded by the adaptive performance governor.
- Scheduled services and closeable resources have explicit plugin lifecycle
  ownership.
- English and Finnish catalogs, permissions, listener registration, plugin
  metadata, and configuration defaults have automated contract coverage.

## Deliberately staged follow-up work

No critical functional rewrite is justified by this audit. Four files are good
future decomposition seams when they next change:

- Split `SqlStorage` into profile, lock, death, spawn, migration, and
  verification query groups behind the existing storage contract.
- Split `SurvivalTweaksCommand` into command-family handlers while retaining one
  registered command entry point.
- Extract first-join location generation and pool persistence from
  `NewPlayerSpawnService`.
- Extract marker persistence and display-entity lifecycle from
  `DeathRecoveryService`.

Doing those as feature-adjacent changes keeps reviewable commits and avoids a
large mechanical rewrite of already-tested behavior.
