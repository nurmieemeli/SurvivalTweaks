<div align="center">

<img src=".github/assets/header.svg" alt="SurvivalTweaks" width="548">

<h3>Vanilla-friendly quality of life for Paper survival servers</h3>

<p>Homes · safe teleports · container locks · death recovery · player hub · bilingual UI</p>

<p>
  <a href="https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml"><img src="https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml/badge.svg" alt="Build and release"></a>
  <a href="https://github.com/nurmieemeli/SurvivalTweaks/releases/latest"><img src="https://img.shields.io/github/v/release/nurmieemeli/SurvivalTweaks" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Paper-26.2-3faffa" alt="Paper 26.2">
  <img src="https://img.shields.io/badge/Java-25-f89820" alt="Java 25">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/nurmieemeli/SurvivalTweaks" alt="MIT license"></a>
</p>

<p>
  <a href="https://github.com/nurmieemeli/SurvivalTweaks/releases/latest">Download latest release</a>
  ·
  <a href="src/main/resources/config.yml">View configuration</a>
  ·
  <a href="docs/operations.md">Operations guide</a>
</p>

</div>

SurvivalTweaks brings the everyday features of a polished survival server into
one dependency-free Paper plugin. Players get a unified, localized experience;
operators get strict configuration validation, diagnostics, backups, and safe
maintenance tools.

The survival loop remains recognizably vanilla. SurvivalTweaks adds no custom
currency, economy, or parallel progression system, and it does not require a
scoreboard, permissions, resource pack, or chat plugin. Its custom enchanted
books use ordinary enchanting, loot, villager, anvil, and grindstone flows.

## Quick start

1. Download the JAR from the
   [latest release](https://github.com/nurmieemeli/SurvivalTweaks/releases/latest).
2. Place it in the Paper server's `plugins/` directory.
3. Start the server with Java 25.
4. Customize `plugins/SurvivalTweaks/config.yml` if needed.
5. Restart, or apply changes with `/survivaltweaks reload`.

The first start creates English and Finnish message catalogs beside the
configuration. Existing installations keep their customized values when new
defaults are introduced.

### Requirements

| Component | Requirement |
| --- | --- |
| Server | Paper 26.2 |
| Java | 25 or newer |
| Optional source build | Maven 3.9 or newer |

## What it adds

| Area | Player experience |
| --- | --- |
| Homes and teleports | Named homes, a paginated home menu, teleport requests, warm-ups, cooldowns, visual feedback, and safe landing searches |
| Container locks | Double-chest-aware locks with trusted, deposit-only, and public access; owner menus; access history; transfer; and per-lock hopper policy |
| Death recovery | Persistent death markers, Nether/Overworld distance scaling, and an automatically restored private floating guide—without a `/back` teleport |
| Player hub | `/survival` brings homes, requests, locks, recovery, notifications, mail, profiles, statistics, guidance, and preferences into one interface |
| Player list | Localized TAB dashboard with status-aware ordering, active/AFK counts, state-change feedback, capacity, ping, TPS, MSPT, dimension, Overworld time/weather, and unread notifications |
| Social tools | Privacy-aware profiles and statistics, highlighted mentions, clickable chat names, welcome-back summaries, and rate-limited text-only offline mail |
| Guided interactions | Failed commands offer localized clickable corrections, while each join condenses pending mail, teleport requests, recovery, maintenance, and restart state into one actionable summary |
| First sessions | Pre-generated unique spawn locations, onboarding, a journey tracker, and contextual explanations of vanilla mechanics |
| Custom enchantments | Tunneling, Excavation, Cultivation, Felling, Beheading, Deflection, and Surefooted through enchanting tables, structure loot, librarians, books, anvils, and grindstones |
| Vanilla refinements | Bounded fast leaf decay, inventory-to-hotbar block refill, pet friendly-fire prevention, sleep voting, and deliberate decoration breaking |
| Atmosphere | Independently configurable sounds and particles for durability, health, pickups, shields, advancements, death sites, weather, caves, biomes, arrows, and interactions |
| Server presentation | Rotating multiplayer-list announcements, localized connection messages, custom environmental death messages, and maintenance status |

Server systems are configurable, and player-facing preferences such as language,
sound, particles, dialogs, action-bar guidance, mentions, and player-list
visibility can be changed in game. Enchantments are item-driven: passive effects
are always active, while area tools require sneaking.

### Custom enchantments

| Enchantment | Equipment | Behavior |
| --- | --- | --- |
| Tunneling I | Pickaxe | Sneak-mine a protected-event-aware 3×3 plane |
| Excavation I | Shovel | Sneak-dig a 3×3 plane of shovel-compatible blocks |
| Cultivation I | Hoe | Sneak-harvest and replant mature crops in a 3×3 area |
| Felling I | Axe | Sneak-fell a natural tree while ignoring player-placed logs |
| Beheading I–III | Sword or axe | Adds a small level-scaled chance for eligible mob or player heads; incompatible with Looting |
| Deflection I | Shield | Returns a properly blocked projectile toward its original shooter |
| Surefooted I | Boots | Prevents farmland trampling |

Enchanting tables can add one eligible custom enchantment as an unadvertised
bonus after the selected vanilla enchantment is applied. The custom result is
intentionally absent from the preview and is revealed after enchanting.
Custom-enchanted books also appear in selected structure loot and librarian
trades, combine through anvils, and are removed by grindstones. The anvil
action bar explains custom compatibility, maximum levels, vanilla cost limits,
and the final level cost without changing Minecraft's cost ceiling.
Every custom-enchanted item also carries localized lore describing its effect,
activation condition, acquisition routes, and relevant incompatibilities.

## Commands

<details>
<summary><strong>Player commands</strong></summary>

| Command | Purpose |
| --- | --- |
| `/survival` or `/st` | Open the unified player hub |
| `/sethome [name]` | Save the current location as a home |
| `/home [name]` | Teleport to a home or browse saved homes |
| `/deletehome [name]` | Delete a home |
| `/teleport <player>` | Request a teleport to another player |
| `/teleportaccept [player]` | Accept a pending request |
| `/teleportinbox` | Browse and respond to pending requests |
| `/deathlocation [guide [on\|off]\|dismiss]` | Manage the latest death marker and recovery guide |
| `/lock` | Lock the targeted container |
| `/lock nearby [radius]` | Lock nearby unlocked containers |
| `/lock trust\|untrust <player>` | Change access to the targeted lock |
| `/lock info` | Inspect the targeted lock |
| `/unlock [confirm]` | Remove the targeted lock |
| `/mail [inbox]` | Open the text-only mailbox |
| `/mail send <player> <message>` | Send offline-safe mail |
| `/mail block\|unblock <player>` | Manage blocked senders |
| `/profile [player]` | Open a player profile |
| `/stats [player]` | Browse vanilla statistics |
| `/welcome` | Open the welcome-back summary |
| `/guide` | Review discovered vanilla guidance |
| `/afk` | Toggle the player-list AFK indicator |
| `/survivaltweaks help` | Show localized, clickable command help |

</details>

<details>
<summary><strong>Operator and console commands</strong></summary>

| Command | Purpose | Default access |
| --- | --- | --- |
| `/shout <message>` | Broadcast a console announcement | Operator or console |
| `/survivaltweaks reload` | Validate and reload settings, languages, and effects | Operator |
| `/survivaltweaks doctor` | Scan configuration and persisted data | Operator |
| `/survivaltweaks performance` | Inspect governor state, fair work lanes, queues, and recent task failures | Operator |
| `/survivaltweaks backup list\|create\|verify` | Inspect and create safety backups | Operator |
| `/survivaltweaks backup restore <file> [confirm]` | Stage a maintenance-only restore | Console |
| `/survivaltweaks storage status\|verify\|export` | Inspect, verify, or export the active database | Operator |
| `/survivaltweaks storage test <sqlite\|postgresql\|mysql>` | Test a configured destination without switching | Operator |
| `/survivaltweaks storage migrate <sqlite\|postgresql\|mysql>` | Stage a verified database migration for restart | Operator |
| `/survivaltweaks spawnpool <status\|refill\|validate\|clear-prepared>` | Manage the first-join spawn pool | Operator |
| `/survivaltweaks maintenance on\|off\|status` | Control join-blocking maintenance mode | Operator |
| `/survivaltweaks restart <10s\|5m\|1h\|cancel\|status>` | Schedule, inspect, or cancel a safe restart | Operator |
| `/survivaltweaks enchant <enchantment> [level]` | Create a custom enchanted book for testing or administration | Operator |

</details>

All aliases and built-in permission nodes are listed in
[`plugin.yml`](src/main/resources/plugin.yml). Operators with
`survivaltweaks.teleport.bypass` skip teleport requests, while
`survivaltweaks.teleport.instant` skips the warm-up.

## Configuration

The bundled [`config.yml`](src/main/resources/config.yml) is organized by
feature:

| Sections | Controls |
| --- | --- |
| `performance` | Adaptive MSPT thresholds, recovery hysteresis, and the shared per-tick budget for batchable work |
| `home`, `storage` | Home limits, SQLite file, remote database connection, pooling, and timeouts |
| `teleport` | Requests, warm-up, cooldown, cancellation, and safe landing |
| `new-player-spawn` | World, coordinate bounds, pool size, spacing, pacing, TPS floor, landing checks, and blocked biomes |
| `locked-containers` | Targeting, limits, explosion protection, and automation defaults |
| `tree-feller`, `fast-leaf-decay` | Felling safety limits and leaf-decay pacing |
| `pet-protection`, `hotbar-refill`, `decoration-protection` | Small interaction safeguards and conveniences |
| `atmosphere`, `feedback` | Ambient, interaction, warning, sound, particle, and trail effects |
| `ui` | Chest interfaces, native dialogs, action bars, and lock hints |
| `death-recovery`, `custom-death-messages` | Markers, an automatic floating guide, expiry, and environmental death variants |
| `chat`, `connection-messages`, `mentions` | Formatting and social notifications |
| `player-list`, `sleep`, `server-list` | Live metrics, AFK behavior and feedback, night voting, and multiplayer-list presentation |
| `updates` | Cached asynchronous GitHub release checks and administrator join-notification delay |
| `journey`, `welcome-back` | First-session progress, vanilla guidance, and return summaries |
| `mail`, `player-profiles`, `statistics` | Availability, privacy, limits, and rate controls |
| `maintenance` | Join blocking and restart safeguards |

`config-version` drives ordered startup migrations. Before changing an older
configuration, SurvivalTweaks creates its normal startup backup, removes known
obsolete settings, preserves custom values, and writes
`config-migration-report.txt` with a concise operator summary. A configuration
from a newer unsupported schema is rejected instead of being rewritten.

Startup replaces unsafe numeric values with logged safe defaults.
`/survivaltweaks reload` is deliberately stricter: it validates the complete
configuration, both language catalogs, MiniMessage templates, particles, and
sound keys before applying anything. A rejected reload leaves the active
configuration unchanged.

Operators with `survivaltweaks.update-notify` receive a direct download link
after joining when the release contains a newer `SurvivalTweaks-<version>.jar`.
The repository is derived from the plugin metadata, and the downloadable
artifact version is compared with the running build. Checks are asynchronous,
cached for six hours by default, and never delay login or server ticks.

The adaptive performance governor observes Paper's MSPT once per second. At the
configured reduced and critical thresholds it lowers cosmetic cadence and
particle density and spreads tree felling, leaf decay, spawn preparation, and
guide/atmosphere work over additional ticks. It recovers gradually after a
sustained healthy period. Persistence, lock enforcement, and teleport safety
are never throttled. Batchable workloads receive separate fair slices of the
shared tick budget, and concurrent tree jobs rotate between players.

## Languages

The plugin automatically selects Finnish for a player whose Minecraft locale is
Finnish; everyone else receives English. Players can override that choice from
the hub, and the change applies immediately.

Both `messages_fi.yml` and `messages_en.yml` are editable MiniMessage catalogs.
New message keys merge into existing files without overwriting server
customizations. Numbers, durations, distances, and item names are rendered for
the receiving player's locale.

Shared text that has no single viewer—such as server-list content, TAB row
markers, restart login messages, and searchable operator diagnostics—uses the
English catalog.

## Data safety and operations

Profiles, locks, death markers, and first-join spawn state share one normalized,
transactional SQL store. SQLite is automatic and requires no setup. PostgreSQL
and MySQL are optional remote backends with bounded connection pooling; their
JDBC drivers are bundled in the plugin JAR.

The first SQL startup imports existing YAML data only when the database is
empty, verifies record counts and a deterministic checksum, and preserves the
original files. The chosen backend and endpoint are then pinned in
`storage-state.yml`. If a configured remote database is unavailable, startup
fails visibly instead of silently writing to SQLite and splitting player data.

Database changes are explicit two-phase operations: configure the remote
endpoint while leaving the active backend selected, run `storage test`, then
run `storage migrate`. On restart, SurvivalTweaks copies the complete logical
snapshot in one transaction, verifies it, and switches the pinned backend only
after success. The source remains authoritative after any failure.

SurvivalTweaks creates safety archives before startup data loading and
configuration reloads, retaining the newest ten. Restores are staged while the
server is running and applied before normal data loading on the next startup.

Maintenance mode blocks new joins without removing connected players. Scheduled
restarts display localized milestones, block late joins, flush pending writes,
and stop Paper cleanly; an external process manager remains responsible for
starting it again.

See the [operations guide](docs/operations.md) for backup restoration,
diagnostics, spawn-pool tuning, and recovery procedures.

## Building from source

Open the repository or its `pom.xml` in IntelliJ IDEA, select a Java 25 SDK, and
run the Maven `verify` lifecycle. The equivalent command-line build is:

```shell
mvn --batch-mode --no-transfer-progress clean verify
```

The resulting plugin is written to
`target/SurvivalTweaks-<version>.jar`. IntelliJ project files, compiler output,
and other local state are intentionally excluded from version control.

## Release quality

Every push and pull request runs the complete Maven test suite and a
checksum-pinned Paper 26.2 smoke test covering startup, configuration reload,
completed diagnostics, and clean shutdown. Recurring runtime subsystems isolate
and report failures so one cosmetic task cannot silently disable its later runs.
CI retains the verified JAR and SHA-256 as
workflow artifacts. A `v*` tag publishes those same files as the latest GitHub
release.

Older profile, lock, and version 1.0 `userdata/` files are upgraded
automatically.

## Contributing and license

Bug reports, feature proposals, and pull requests are welcome. Read
[CONTRIBUTING.md](CONTRIBUTING.md) before making a change and follow the
[Code of Conduct](CODE_OF_CONDUCT.md) in project spaces.

SurvivalTweaks is available under the [MIT License](LICENSE).
