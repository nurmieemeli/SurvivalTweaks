<div align="center">

<img src=".github/assets/header.svg" alt="SurvivalTweaks" width="548">

[![Build and release](https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml/badge.svg)](https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/nurmieemeli/SurvivalTweaks)](https://github.com/nurmieemeli/SurvivalTweaks/releases/latest)

</div>

A Paper plugin for a Finnish survival server. It adds homes, teleport requests,
container locks, death recovery, offline mail, and a unified player hub — all
localized in Finnish and English, with no scoreboard or permissions plugin
required.

Everything is optional and configurable, and nothing changes vanilla outcomes:
the plugin observes play rather than altering recipes, drops, or restrictions.

## Requirements

- Paper 26.2 (compiled against `26.2.build.84-stable`)
- Java 25 or newer
- Maven 3.9 or newer for command-line builds

## Building

```shell
mvn verify
```

The JAR is written to `target/SurvivalTweaks-<version>.jar`. `verify` runs the
full unit suite and produces the same layout CI publishes.

In IntelliJ IDEA, open the repository root or its `pom.xml` as a Maven project
and select a Java 25 SDK. The Maven model is the shared project definition;
local `.idea`, module, and compiler-output files are intentionally ignored.

## Installation

1. Build the plugin.
2. Copy the JAR into the server's `plugins/` directory.
3. Start the server.
4. Adjust `plugins/SurvivalTweaks/config.yml` or either message catalog.
5. Run `/survivaltweaks reload`, or restart.

Older profile, lock, and version 1.0 `userdata/` files are upgraded
automatically, and new configuration defaults are merged into existing files
without replacing customized values.

## Commands

| Command | Description | Default access |
| --- | --- | --- |
| `/sethome [name]` | Set a home at the current location | Everyone |
| `/home [name]` | Teleport to a home or list available homes | Everyone |
| `/deletehome [name]` | Delete a home | Everyone |
| `/teleport <player>` | Request a teleport to another player | Everyone |
| `/teleportaccept [player]` | Accept a pending request | Everyone |
| `/teleportinbox` | Browse and respond to pending teleport requests | Everyone |
| `/afk` | Toggle your player-list AFK indicator | Everyone |
| `/mail [inbox]` | Open your text-only mailbox | Everyone |
| `/mail send <player> <message>` | Send offline-safe player mail | Everyone |
| `/mail block\|unblock <player>` | Control who may send you mail | Everyone |
| `/profile [player]` | Open your own or another player's public profile | Everyone |
| `/stats [player]` | Browse a vanilla statistics journal | Everyone |
| `/welcome` | Open the welcome-back summary | Everyone |
| `/guide` | Review vanilla mechanics discovered while playing | Everyone |
| `/deathlocation [guide [on\|off]\|dismiss]` | Manage the latest death marker and its recovery aids | Everyone |
| `/lock` | Lock the targeted container | Everyone |
| `/lock nearby [radius]` | Lock unlocked containers within 1–8 blocks | Everyone |
| `/lock trust\|untrust <player>` | Manage access to the targeted lock | Everyone |
| `/lock info` | Show lock ownership and access | Everyone |
| `/unlock [confirm]` | Confirm and remove the targeted lock | Everyone |
| `/survival` or `/st` | Open the unified player hub | Everyone |
| `/survivaltweaks help` | Show localized, clickable commands available to you | Everyone |
| `/shout <message>` | Broadcast a console announcement | Operators/console |
| `/survivaltweaks reload` | Validate and reload settings, languages, and effects | Operators |
| `/survivaltweaks doctor` | Scan configuration, player data, locks, markers, and backups | Operators |
| `/survivaltweaks backup list\|create\|verify` | Manage safety backups | Operators |
| `/survivaltweaks backup restore <file> [confirm]` | Stage a maintenance-only restore | Console |
| `/survivaltweaks spawnpool <status\|refill\|validate\|clear-prepared>` | Manage the first-join spawn pool | Operators |
| `/survivaltweaks maintenance on\|off\|status` | Control join-blocking maintenance mode | Operators |
| `/survivaltweaks restart <10s\|5m\|1h\|cancel\|status>` | Schedule or cancel a safe restart | Operators |

Operators with `survivaltweaks.teleport.bypass` teleport immediately instead of
creating a request. All permission nodes and aliases are documented in
[`plugin.yml`](src/main/resources/plugin.yml).

## Features

**Homes and teleports.** Multiple homes open in a paginated chest menu where
each one can take an icon, description, category, arrival style, favorite mark,
and custom order. Teleport requests use a warm-up with a boss bar, action-bar
timer, countdown sounds, and arrival effects, and land on a safe nearby block.

**Player hub.** `/survival` links to homes, the teleport inbox, death recovery,
managed locks, notifications, mail, profiles, statistics, the journey tracker,
and per-player experience preferences — sounds, particles, dialogs, action-bar
guidance, player-list visibility, mention notifications, and language.

**Container locks.** Locks cover both halves of a double chest and support
trusted-only, deposit-only, and public access. Owners shift-right-click a lock
to rename it, manage trusted players, review recent access attempts, transfer
ownership, and allow or block hopper automation. Locks resist explosions and
fire, and hopper automation stays blocked unless enabled per lock.

**Death recovery.** Each death records a marker and can grant a bound recovery
compass on respawn, reporting distance and direction with scaled hints between
the Overworld and Nether. Right-clicking air toggles a private floating guide
visible only to its owner. There is no `/back` teleport.

**Player list.** A localized TAB header and footer show capacity, the viewer's
ping, one-minute TPS, average MSPT, current dimension, and unread notifications.
Rows carry dimension markers, an optional staff badge, and AFK labels. Players
go AFK automatically or with `/afk`; the state is session-only.

**Social.** Typing `@Name` highlights a mention for that player without
revealing hidden players. Profiles and vanilla statistics journals are
privacy-aware, work for offline players, and need
`survivaltweaks.profile.bypass` to inspect when hidden. Mail is text-only, safe
to send offline, and rate-limited — no items, currency, or commands move.

**First sessions.** New players can spawn at unique, pre-generated Overworld
locations so terrain generation never delays their first moments. A journey
tracker completed by normal play links to `/guide`, which explains Nether
coordinates, sleep rules, villager curing, respawn anchors, lodestones, anvils,
and enchanting the first time each is encountered.

**Server touches.** A configurable share of active players can skip the night,
with AFK players optionally excluded. The multiplayer server list rotates
MiniMessage announcements and reflects maintenance and restart states without a
proxy plugin. Environmental deaths can use subtle localized variants, while
player-, mob-, and weapon-attributed deaths keep Minecraft's native message.

## Configuration

`config.yml` is grouped by feature:

| Section | Controls |
| --- | --- |
| `home`, `storage` | Home limit and profile autosave interval |
| `teleport` | Request lifetime, warm-up, cooldown, cancellation, safe search |
| `new-player-spawn` | First-join pool: world, bounds, size, separation, pacing, TPS floor, landing checks, biomes |
| `locked-containers` | Target distance, per-player limit, explosion protection, automation policy |
| `ui` | Chest menus, native dialogs, action bar, lock targeting hints |
| `death-recovery` | Markers, respawn compass, floating guide, lifetime, cooldown |
| `custom-death-messages` | Environmental death lines, rare-variant frequency, per-cause switches |
| `feedback` | Per-action sounds and particles, each independently configurable |
| `chat`, `connection-messages` | Chat format and join/quit messages |
| `player-list` | Metrics, refresh interval, staff badges, AFK timeout |
| `sleep` | Threshold, AFK exclusion, weather clearing |
| `server-list` | Rotating announcements |
| `mentions` | Cooldown and per-message cap |
| `mail` | Length, cooldown, hourly limit |
| `journey`, `welcome-back` | First-session guidance, vanilla guide topics, return summaries |
| `player-profiles`, `statistics` | Availability and public viewing |
| `maintenance` | Restart join blocking and pre-restart backups |

Startup tolerates invalid numeric settings by falling back to safe defaults and
logging a warning. `/survivaltweaks reload` is stricter: it validates the whole
configuration, both catalogs, MiniMessage templates, particles, and sound keys
before applying anything, and a rejected reload leaves the running configuration
untouched.

## Languages

Each player's Minecraft client locale selects their language: `fi` uses
`messages_fi.yml`, everything else and the console use `messages_en.yml`.
Players can override this from `/survival`, and changes apply immediately.

Both catalogs are copied to `plugins/SurvivalTweaks/` and accept MiniMessage
formatting. New keys merge into existing catalogs without overwriting
customizations. Durations and distances take their units from the catalogs too,
so hours, minutes, and kilometres read naturally in either language, and
decimals follow the reader's language — a Finnish player sees `1,2 km` and
`12,5` damage where an English one sees `1.2 km` and `12.5`.

A few strings always come from `messages_en.yml`, because no single reader's
language applies:

- the server-list status and announcements, and the kick shown when a scheduled
  restart blocks a login — both happen before the player's locale is known;
- the TAB staff and AFK markers, which are one shared row rendered identically
  for every viewer;
- operator output from `/survivaltweaks doctor` and backup verification, which
  stays English so it can be searched and quoted directly.

Editing those keys in `messages_en.yml` changes them for everyone; editing them
in `messages_fi.yml` has no effect.

## Operations

Profiles, locks, death markers, and spawn state persist through an ordered
coalescing background writer with atomic file replacement, so no Bukkit object
or mutable collection crosses a thread boundary. The plugin snapshots its data
into `plugins/SurvivalTweaks/backups/` before loading at startup and before
every reload, keeping the newest ten archives.

Maintenance mode blocks new joins while leaving connected players alone.
Scheduled restarts show localized milestones and a boss bar, block late joins in
the final window, flush profile writes, and shut Paper down cleanly; an external
process manager is responsible for starting it again.

See [`docs/operations.md`](docs/operations.md) for backup and restore
procedures, spawn-pool tuning, diagnostics, and the data-safety guarantees.

## Release QA

Every push and pull request runs the Maven suite and a pinned Paper 26.2
startup/reload/diagnostics/shutdown smoke test, then retains the verified JAR
and its SHA-256 as a workflow artifact. Pushing a `v*` tag publishes that same
verified artifact as the latest GitHub release.
