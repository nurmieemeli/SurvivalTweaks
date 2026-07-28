# SurvivalTweaks

[![Build and release](https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml/badge.svg)](https://github.com/nurmieemeli/SurvivalTweaks/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/nurmieemeli/SurvivalTweaks)](https://github.com/nurmieemeli/SurvivalTweaks/releases/latest)

SurvivalTweaks is a small Paper plugin for a Finnish survival server. It provides
customizable homes, player-to-player teleport requests, persistent death
recovery, a compact chat format, localized connection messages, console
announcements, manageable container locks, and a unified player hub with
per-player experience settings. Its localized player list adds server health,
personal connection details, dimension markers, notifications, and AFK status
without requiring a scoreboard or permissions plugin. Smart sleep voting,
player mentions, a guided first-session journey, a rotating server-list
presentation, and supervised restart tooling smooth out everyday play and
server operation. Returning-player summaries, privacy-aware player profiles,
vanilla statistics journals, and text-only offline mail keep the community
connected without introducing item-transfer risks. An optional contextual guide
also explains subtle vanilla mechanics when players encounter them, without
changing their outcomes. Environmental deaths can use subtle localized variants
while player-, mob-, projectile-, and weapon-attributed deaths remain native.

New players can also be placed at unique, pre-generated Overworld starting
locations so terrain generation never interrupts their first moments.

## Requirements

- Paper 26.2
- Java 25 or newer
- Maven 3.9 or newer for command-line builds

The project compiles against the pinned Paper API build
`26.2.build.84-stable`.

## Building

```shell
mvn verify
```

The plugin JAR is written to `target/SurvivalTweaks-<version>.jar`.

### IntelliJ IDEA

Open the repository root or its `pom.xml` as a Maven project, select a Java 25
SDK, and use IntelliJ IDEA's bundled Maven installation to import dependencies
and run lifecycle tasks. IDEA's local `.idea`, module, compiler-output, and
run-server files are intentionally ignored; the Maven model is the shared
project definition.

Use the Maven `verify` lifecycle before committing. It runs the complete unit
suite and produces the same JAR layout used by CI.

## Installation

1. Build the plugin.
2. Copy the JAR from `target/` to the Paper server's `plugins/` directory.
3. Start the server.
4. Edit `plugins/SurvivalTweaks/config.yml` or either message catalog if needed.
5. Run `/survivaltweaks reload`, or restart the server.

Existing `userdata/<uuid>.yml` home files from version 1.0 are read
automatically and queued for migration into the safer versioned schema with both
world UUID and name. Older profile and lock schemas are upgraded automatically.
New configuration defaults are merged into existing configuration files without
replacing customized values.

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
| `/stats [player]` | Browse your own or another player's vanilla statistics journal | Everyone |
| `/welcome` | Open the welcome-back summary | Everyone |
| `/guide` | Review vanilla mechanics discovered naturally while playing | Everyone |
| `/deathlocation [guide [on\|off]\|dismiss]` | Manage the latest death marker, compass, and private floating guide | Everyone |
| `/shout <message>` | Broadcast a console announcement | Operators/console |
| `/lock` | Lock the targeted container | Everyone |
| `/lock nearby [radius]` | Lock unlocked containers in a nearby 1–8 block radius | Everyone |
| `/lock trust <player>` | Allow an online player to use the targeted lock | Everyone |
| `/lock untrust <player>` | Remove a player's access | Everyone |
| `/lock info` | Show lock ownership and access | Everyone |
| `/unlock [confirm]` | Confirm and remove the targeted lock | Everyone |
| `/survival` or `/st` | Open the unified player hub | Everyone |
| `/survivaltweaks help` | Show localized, clickable commands available to you | Everyone |
| `/survivaltweaks reload` | Validate and reload settings, languages, and effects | Operators |
| `/survivaltweaks doctor` | Scan configuration, player data, locks, markers, compasses, and backups | Operators |
| `/survivaltweaks backup list\|create` | List or manually create safety backups | Operators |
| `/survivaltweaks backup verify <file>` | Validate an archive and report its SHA-256 | Operators |
| `/survivaltweaks backup restore <file> [confirm]` | Stage a maintenance-only restore for next startup | Console |
| `/survivaltweaks spawnpool status` | Show pool readiness, assignments, TPS, and generation metrics | Operators |
| `/survivaltweaks spawnpool refill` | Request TPS-aware pool replenishment | Operators |
| `/survivaltweaks spawnpool validate` | Revalidate prepared and pending locations asynchronously | Operators |
| `/survivaltweaks spawnpool clear-prepared [confirm]` | Confirm clearing only unassigned prepared locations | Operators |
| `/survivaltweaks maintenance on\|off\|status` | Control join-blocking maintenance mode | Operators |
| `/survivaltweaks restart <10s\|5m\|1h\|cancel\|status>` | Schedule, inspect, or cancel a safe restart | Operators |

Operators with `survivaltweaks.teleport.bypass` teleport immediately instead of
creating a request. All permission nodes and aliases are documented in
`src/main/resources/plugin.yml`.

## Configuration

`config.yml` controls:

- maximum homes per player;
- profile autosave interval;
- teleport-request lifetime;
- teleport warm-up, cooldown, cancellation, and safe-location search;
- first-join Overworld selection, coordinate bounds, prepared-location pool
  size, minimum separation, generation pacing, minimum TPS, landing-area size
  and slope, biome allow/block lists, and generation-attempt limit;
- locked-container distance, per-player limit, explosion protection, and
  automation policy;
- chest-style home menus and confirmation dialogs;
- persistent death markers, recovery-compass delivery, floating-guide behavior,
  lifetime, and cooldown;
- localized environmental death messages, rare-line frequency, and individual
  cause switches;
- teleport action-bar timers and locked-container targeting hints;
- per-action sounds and particles, each independently configurable;
- chat and connection-message formatting;
- localized player-list metrics, refresh interval, staff badges, and AFK
  timeout;
- smart sleep threshold, AFK exclusion, and weather clearing;
- rotating server-list announcements;
- mention rate limits and per-player mention notifications;
- restart join blocking and pre-restart backups;
- optional first-session journey guidance, welcome timing, contextual vanilla
  topics, and the minimum interval between explanations;
- welcome-back timing and optional automatic summary opening;
- player-profile availability;
- vanilla statistics-journal availability and public viewing;
- mail length, cooldown, and hourly rate limits;
- Finnish and English MiniMessage catalogs.

Startup remains tolerant of invalid numeric settings by using safe defaults and
logging a warning. `/survivaltweaks reload` is stricter: it validates the entire
configuration, both language catalogs, MiniMessage templates, particles, and
sound-key syntax before applying anything. A rejected reload leaves the previous
runtime configuration active.

## Languages

By default, the plugin reads each player's current Minecraft client locale.
Locales whose language code is `fi` use `messages_fi.yml`; all other player
locales and the server console use `messages_en.yml`. Players can override
automatic detection with Finnish or English from `/survival`. Locale and
preference changes take effect immediately.

Both files are copied to `plugins/SurvivalTweaks/` and can be customized with
MiniMessage formatting. New message keys are merged into existing catalogs
without overwriting customized values. Messages customized in the old
`config.yml` layout are migrated into `messages_fi.yml` on first startup.

## Data safety

Profiles are loaded before they become available to commands. Homes retain their
icon, description, favorite state, category, arrival style, ordering, and
UUID-first world reference. The same versioned profile stores player
preferences—including player-list, mention, journey-guidance, profile-privacy,
and mail toggles—one-time onboarding progress, last-seen and cached playtime
metadata, UUID-based mail blocks, and a capped notification inbox.
Saves use one ordered, coalescing background writer and atomic file replacement,
so Bukkit objects and mutable collections never cross thread boundaries.
Unchanged and intermediate snapshots are skipped. Player data is saved after
changes, on disconnect, during periodic autosaves, and during plugin shutdown.

The latest death marker for each player is stored in `death-markers.yml` using
both world UUID and name. Markers survive restarts and expire according to the
configured lifetime.

Prepared and assigned first-join destinations are stored in
`new-player-spawns.yml`. Each entry uses both world UUID and name. A location is
persistently reserved before teleporting, completed after a successful
placement, and retained so future random locations respect the configured
minimum separation. Pending placement is retried when the player reconnects.
Available destination chunks are generated asynchronously and held loaded with
plugin chunk tickets until assigned.

Generation runs one candidate at a time, waits the configured number of ticks
between candidates, and pauses below the configured one-minute TPS threshold.
The complete landing square must have safe footing, headroom, acceptable height
variation, and an allowed biome. Ocean biomes are blocked by default. An empty
allow list accepts any biome not explicitly blocked.

If an assigned destination becomes unsafe or its world disappears before the
first teleport completes, the location is permanently retired and a replacement
is atomically reserved. Players waiting for a replacement remain eligible
across restarts. Operators can inspect runtime rejection and replacement
metrics, trigger replenishment, validate the pool, or clear only unassigned
prepared entries with `/survivaltweaks spawnpool`.

Before startup can load or migrate data, and before every configuration reload,
the plugin creates an atomic ZIP snapshot in
`plugins/SurvivalTweaks/backups/`. Backups contain configuration, both language
catalogs, locks, death markers, first-join spawn state, and profile YAML files.
The newest ten archives are retained. `/survivaltweaks doctor` verifies every
archive and scans the remaining data asynchronously for invalid schemas,
unresolved worlds, overlapping locks, stale online recovery compasses, and
other operational problems. Its worker is cancelled and joined during plugin
shutdown so a late report cannot outlive the plugin classloader.

Operators can list, create, and individually verify archives with
`/survivaltweaks backup`. Restoration is deliberately staged: maintenance mode
must be enabled, no players may remain online, and the final confirmation must
come from the local server console. The selected archive is copied to a
protected pending file and Paper shuts down. On the next startup, SurvivalTweaks
creates a fresh `pre-restore` backup, verifies the staged archive again, swaps
only plugin-managed files with rollback protection, and applies current
defaults before loading any player or world data.

## Locked containers

Container locks use world UUIDs and block coordinates, support both halves of a
double chest, and persist independently in `locked-containers.yml`. Owners can
shift-right-click their lock to open a control panel for naming it, changing its
access mode, managing online or previously seen trusted players, reviewing
recent access attempts, transferring ownership, and choosing whether hopper
automation is allowed. Access modes include trusted-only, deposit-only, and
public. `/survival` also provides a paginated browser for every lock the player
can manage. Administrators can manage locks too. Locked containers are
protected from explosions and fire. Hopper automation involving a lock remains
blocked by default unless explicitly enabled for that lock.

## Player interfaces

`/survival` (also `/st`) opens the central chest interface. It links to homes,
the teleport inbox, death recovery, managed locks, notifications, experience
preferences, the optional journey tracker, and localized command help.
It also links directly to the player's mailbox, profile, vanilla statistics
journal, and welcome-back summary.

Brand-new players receive a localized, delayed welcome and can follow the
journey tracker at their own pace. Its objectives are completed by normal play,
not by a forced tutorial. Guidance can be disabled independently without losing
recorded progress.

The journey tracker links to `/guide`, a discovery-based vanilla reference.
First encounters with Nether travel, failed sleep, zombie-villager curing,
respawn anchors, lodestones, anvils, and enchanting produce one concise,
clickable explanation. A shared cooldown prevents overlapping hints and defers
rare discoveries until the player can receive them. Each topic can be disabled
independently. The listeners observe these actions but never cancel events,
change recipes, automate interactions, or relax vanilla restrictions.

The TAB player list has a per-viewer localized header and footer. It shows
online capacity, the viewer's color-coded ping, one-minute TPS, average MSPT,
current dimension, and unread notification count. Player rows use unobtrusive
dimension markers, stable alphabetical ordering, an optional permission-based
staff star, and an AFK label. Players become AFK after the configured idle
period or can toggle the state with `/afk`; movement, looking around, chatting,
commands, inventory use, and interactions clear it. AFK state is intentionally
session-only. The dashboard can be hidden per player from `/survival` without
disabling shared row indicators.

Typing an online player's exact Minecraft name as `@Name` highlights that
mention for the recipient and plays a configurable cue. Hidden players are not
revealed, self-mentions are ignored, duplicate names are collapsed, and
per-sender/recipient cooldowns prevent notification spam. Recipients can disable
mention notifications from the preferences menu; senders are told when the
mentioned player is AFK.

In the Overworld, the configured percentage of eligible active players can skip
the night. AFK players can be excluded from the denominator, progress is shown
without overriding higher-priority action-bar guidance, and weather can clear
with the completed vote. SurvivalTweaks restores the world's original vanilla
sleep percentage whenever this feature is disabled or the plugin shuts down.

The multiplayer server list uses a two-line status with rotating MiniMessage
announcements. It reflects online, maintenance, scheduled restart, and actively
restarting states without requiring a proxy plugin.

Players returning after the configured absence receive a compact localized
prompt rather than a forced menu by default. `/welcome` summarizes unread
notifications and mail, live teleport requests, death-marker status, time away,
and the server's current what's-new note.

`/profile [player]` shows a privacy-aware chest interface with online/AFK
status, cached playtime, first-join age, home count, and safe social actions.
Vanished players are presented as offline, profile visibility is controlled by
each player, and operators need the explicit `survivaltweaks.profile.bypass`
permission to inspect private profiles.

`/stats [player]` presents a read-only journal assembled from statistics
Minecraft already records: playtime, travel, combat, activities, and favorite
tools. It works for previously seen offline players and follows the same public
profile privacy setting and explicit bypass permission. The plugin does not
create a parallel progression system or alter any statistic.

Player mail is text-only and remains available when the recipient is offline.
Messages are stored as notifications with the sender's UUID, so replies and
blocks remain correct after name changes. Recipients can reply, delete, or
block directly from the mailbox and can disable mail entirely. Message length,
per-send cooldown, and hourly limits prevent spam; no items, currency, or
commands are transported.

When a player has multiple homes, `/home` opens a paginated chest-style
selector. Right-clicking a home opens its editor for choosing an icon, adding a
description, marking it as a favorite, assigning a category and arrival style,
renaming it, or updating its location. Shift-click one home and then another to
move it safely without exposing inventory cursor state.

`/teleportinbox` presents all pending teleport requests in one chest interface;
left-click accepts and right-click declines. Individual incoming requests and
`/unlock` use native client dialogs when enabled. Equivalent commands remain
available if dialogs are disabled or cannot be shown.

On death, SurvivalTweaks records the location and can place a bound recovery
compass in the player's inventory after respawn. The compass reports distance in
the same world with directional arrows, distance coloring, and nearby/reached
feedback. It provides scaled coordinate hints between the Normal world and
Nether, and warns when the marker is otherwise in another world or has expired.
Right-clicking air with the recovery compass toggles a private floating guide.
While far away, its display remains near the player in the direction of the
marker. Inside the configured near distance, it moves to the exact death site.
Overworld/Nether dimension changes instead guide toward scaled portal
coordinates. The non-persistent display is visible only to its owner and is
removed on arrival, dismissal, expiry, disconnect, or shutdown.
`/deathlocation` restores a missing compass subject to a cooldown and offers a
clickable guide toggle. `/deathlocation guide [on|off]` is its command fallback,
while `/deathlocation dismiss` removes the marker and all recovery aids. Death
recovery does not include a `/back` teleport.

Teleport warm-up uses a boss bar, action-bar timer, final countdown sounds,
destination title, and origin/arrival particles. Looking at a locked container
shows whether it belongs to the viewer, grants them access, or denies access.
Teleport status has higher display priority, while death-compass guidance stays
above container hints so low-priority hints cannot overwrite active guidance.
Every effect family can be disabled or reduced per player from the hub.

Paper currently marks its dialog API as experimental, so all dialog code is
isolated from command and persistence logic.

## Maintenance and restarts

Operators can enable maintenance mode to reject new non-bypass joins while
leaving connected players undisturbed. Scheduled restarts accept explicit
second, minute, or hour units, show localized milestone messages and a live boss
bar, and block late joins during the configured final window. At zero, players
receive a friendly localized disconnect, profile writes are flushed, an
optional safety backup is created, and Paper is shut down cleanly. An external
process manager should be responsible for starting the server again.

## Release QA

Every push and pull request runs the Maven suite and a pinned Paper 26.2
startup/reload/diagnostics/shutdown smoke test, then retains the verified JAR
and SHA-256 as a workflow artifact. A pushed `v*` tag automatically publishes
that same verified artifact as the latest GitHub release.

Automated tests and the headless Paper smoke test cover storage, migrations,
commands, startup, reload, backup recovery, and clean shutdown. Player-rendered
menus, dialogs, sounds, particles, and multi-account permissions should be
checked manually on a disposable multiplayer server before deploying to a
production world.

Runtime work is deliberately event-driven. Idle teleport, death-guide, and
sleep-vote tasks stop themselves; player-list and action-bar updates are sent
only when visible state changes. Profile, death-marker, spawn-pool, reload, and
backup disk work runs away from the server thread, with immutable snapshots and
shutdown drains preserving durability.
