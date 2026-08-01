# Architecture

SurvivalTweaks is a single Paper plugin with an explicit composition root in
`SurvivalTweaks`. Feature classes receive their dependencies through
constructors; they do not retrieve global plugin state. Listeners translate
Paper events into service calls, while controllers own inventory and dialog
presentation.

## Runtime shape

```text
Paper events and commands
        |
        v
listeners / command router / UI controllers
        |
        v
feature services and lifecycle coordinators
        |
        +----> SettingsService + MessageService
        +----> ProfileRepository
        +----> lock / death / spawn repositories
        |
        v
StorageManager -> SqlStorage -> SQLite, PostgreSQL, or MySQL
```

`SurvivalTweaks` is intentionally the only composition root. It validates and
migrates configuration, opens storage, creates repositories and services,
registers listeners and commands, and finally starts recurring tasks. Shutdown
stops producers first, captures active player sessions, drains persistence, and
then closes storage.

## State ownership

| State | Owner | Persistence |
| --- | --- | --- |
| Validated runtime settings | `SettingsService` | `config.yml` |
| Localized message catalogs | `MessageService` | `messages_en.yml`, `messages_fi.yml` |
| Player profiles and write coalescing | `ProfileRepository` | SQL profile aggregate |
| Current login identity, play time, and prior-seen timestamp | `PlayerSessionService` | Through `ProfileRepository` |
| Join ordering and feedback budget | `JoinExperienceCoordinator` | None |
| Pending teleport requests | `TeleportRequestService` | Session memory |
| Teleport warm-ups and safe destinations | `SafeTeleportService` | Session memory |
| Container locks | `ContainerLockService` | SQL lock aggregate |
| Death marker and guide lifecycle | `DeathRecoveryService` | SQL death marker aggregate |
| Prepared first-join locations | `NewPlayerSpawnService` | SQL spawn aggregate |
| Mail, notifications, preferences, and journey progress | Their feature services | Through player profiles |
| Database selection, migrations, import, verification, and lease | `StorageManager` | SQL plus `storage-state.yml` |
| Runtime health and adaptive work limits | `OperationalHealthService`, `PerformanceGovernor` | None |

Do not update `lastKnownName`, `lastSeenAt`, or `playTimeTicks` outside
`PlayerSessionService`. Storage codecs may read and write those fields when
serializing a profile. `ArchitectureContractTest` enforces this boundary.

## Threading contract

- Bukkit worlds, entities, inventories, audiences, and schedulers are accessed
  on the server thread unless a Paper API explicitly permits otherwise.
- Async pre-login may load profile data, but it does not touch world state.
- Database snapshots, backups, release checks, and portable exports may run on
  worker threads. Their player-facing completion work returns to the server
  thread.
- Mutable caches shared with workers use concurrent collections or synchronized
  ownership. Persistent writes pass immutable snapshots across the boundary.
- Recurring tasks report failures through `TaskFailureMonitor`; one failed
  cosmetic iteration must not silently cancel later runs.

## Configuration reload

Reload is a prepare/apply transaction:

1. Create a safety backup.
2. Read `config.yml` once and fingerprint its bytes.
3. Validate its schema, storage settings, exports, runtime settings, languages,
   MiniMessage templates, sounds, and particles without changing live state.
4. Reject the reload if the file changed during validation.
5. Apply the exact validated configuration to every runtime consumer.
6. Roll back settings, catalogs, feedback, and consumers if application fails.

This prevents a validated file and a separately reread file from producing a
mixed runtime configuration.

## Persistence lifecycle

SQLite is the zero-configuration backend. PostgreSQL and MySQL are explicit
operator choices; an unavailable configured remote store fails startup rather
than silently splitting data into SQLite. Migrations stage a complete logical
snapshot, verify record counts and checksum, and pin the new backend only after
success. Remote stores hold a database-session lease and produce verified
portable SQLite exports.

Repositories coalesce repeated updates and drain their queues during shutdown.
Aggregate SQL writes compare stable keys and update only changed rows. Database
maintenance requires maintenance mode, an empty server, and a verified safety
export.

## Extension rules

When adding a feature:

1. Give persistent or session state one named owner.
2. Put Paper event translation in a small listener and behavior in a service.
3. Route overlapping join feedback through `JoinExperienceCoordinator`.
4. Pass validated settings into services; do not parse `config.yml` in event
   handlers.
5. Keep database work off the server thread and return Bukkit work to it.
6. Add lifecycle, resource, locale, and permission coverage where applicable.
7. Ensure every scheduled task and `AutoCloseable` has an explicit shutdown
   path in `SurvivalTweaks`.

Large classes are deliberate follow-up seams rather than invitations to a
high-risk rewrite. `SqlStorage`, `SurvivalTweaksCommand`,
`NewPlayerSpawnService`, and `DeathRecoveryService` should be decomposed by
cohesive query group or workflow when their next functional change requires it.
