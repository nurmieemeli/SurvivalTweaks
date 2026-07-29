<div align="center">

<img src=".github/assets/header.svg" alt="SurvivalTweaks" width="548">

<h3>Vanilla-friendly quality of life for Paper survival servers</h3>

<p>Homes · safe teleports · container locks · death recovery · player hub · bilingual UI</p>

<p>
  <a href="https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml"><img src="https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml/badge.svg" alt="Build and release"></a>
  <a href="https://github.com/nurmieemeli/SurvivalTweaks/releases/latest"><img src="https://img.shields.io/github/v/release/nurmieemeli/SurvivalTweaks" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Paper-26.2-3faffa" alt="Paper 26.2">
  <img src="https://img.shields.io/badge/Java-25-f89820" alt="Java 25">
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
items, recipes, currency, economy, or progression system, and it does not
require a scoreboard, permissions, or chat plugin.

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
| Death recovery | Persistent death markers, a bound recovery compass, Nether/Overworld distance scaling, and a private floating guide—without a `/back` teleport |
| Player hub | `/survival` brings homes, requests, locks, recovery, notifications, mail, profiles, statistics, guidance, and preferences into one interface |
| Player list | Localized TAB header and footer with capacity, ping, TPS, MSPT, dimension, unread notifications, staff badges, and automatic or manual AFK state |
| Social tools | Privacy-aware profiles and statistics, highlighted mentions, welcome-back summaries, and rate-limited text-only offline mail |
| First sessions | Pre-generated unique spawn locations, onboarding, a journey tracker, and contextual explanations of vanilla mechanics |
| Vanilla refinements | Optional tree felling, bounded fast leaf decay, inventory-to-hotbar block refill, pet friendly-fire prevention, sleep voting, and deliberate decoration breaking |
| Atmosphere | Independently configurable sounds and particles for durability, health, pickups, shields, advancements, death sites, weather, caves, biomes, arrows, and interactions |
| Server presentation | Rotating multiplayer-list announcements, localized connection messages, custom environmental death messages, and maintenance status |

Every feature is configurable, and player-facing preferences such as language,
sound, particles, dialogs, action-bar guidance, mentions, and player-list
visibility can be changed in game.

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
| `/survivaltweaks backup list\|create\|verify` | Inspect and create safety backups | Operator |
| `/survivaltweaks backup restore <file> [confirm]` | Stage a maintenance-only restore | Console |
| `/survivaltweaks spawnpool <status\|refill\|validate\|clear-prepared>` | Manage the first-join spawn pool | Operator |
| `/survivaltweaks maintenance on\|off\|status` | Control join-blocking maintenance mode | Operator |
| `/survivaltweaks restart <10s\|5m\|1h\|cancel\|status>` | Schedule, inspect, or cancel a safe restart | Operator |

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
| `home`, `storage` | Home limits and profile persistence |
| `teleport` | Requests, warm-up, cooldown, cancellation, and safe landing |
| `new-player-spawn` | World, coordinate bounds, pool size, spacing, pacing, TPS floor, landing checks, and blocked biomes |
| `locked-containers` | Targeting, limits, explosion protection, and automation defaults |
| `tree-feller`, `fast-leaf-decay` | Forestry activation, work limits, and decay pacing |
| `pet-protection`, `hotbar-refill`, `decoration-protection` | Small interaction safeguards and conveniences |
| `atmosphere`, `feedback` | Ambient, interaction, warning, sound, particle, and trail effects |
| `ui` | Chest interfaces, native dialogs, action bars, and lock hints |
| `death-recovery`, `custom-death-messages` | Markers, compass, floating guide, expiry, cooldowns, and environmental death variants |
| `chat`, `connection-messages`, `mentions` | Formatting and social notifications |
| `player-list`, `sleep`, `server-list` | Live metrics, AFK behavior, night voting, and multiplayer-list presentation |
| `journey`, `welcome-back` | First-session progress, vanilla guidance, and return summaries |
| `mail`, `player-profiles`, `statistics` | Availability, privacy, limits, and rate controls |
| `maintenance` | Join blocking and restart safeguards |

Startup replaces unsafe numeric values with logged safe defaults.
`/survivaltweaks reload` is deliberately stricter: it validates the complete
configuration, both language catalogs, MiniMessage templates, particles, and
sound keys before applying anything. A rejected reload leaves the active
configuration unchanged.

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

Profiles, locks, death markers, and spawn state use ordered background
persistence with atomic file replacement. Mutable Bukkit state never crosses
the asynchronous storage boundary.

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
diagnostics, and clean shutdown. CI retains the verified JAR and SHA-256 as
workflow artifacts. A `v*` tag publishes those same files as the latest GitHub
release.

Older profile, lock, and version 1.0 `userdata/` files are upgraded
automatically.
