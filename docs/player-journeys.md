# Player journeys

These journeys define the intended order, safety properties, and feedback
budget of the main player-facing workflows. They complement unit tests by
making cross-feature behavior explicit.

## First join

1. Async pre-login loads the profile or rejects login with a stable message.
2. `PlayerSessionService` starts the online session and records identity.
3. `NewPlayerSpawnService` claims a unique prepared Overworld destination. If
   none is ready, its bounded fallback still applies the configured safety
   checks.
4. The experience service primes per-player locale and preference state.
5. Onboarding and journey guidance begin after the player is placed safely.
6. Operator health and update notices are shown only to authorized players.

The player must never briefly appear at a random unsafe coordinate, receive a
returning-player summary, or claim the same prepared location as another new
player.

## Returning join

1. The session owner captures the previous persisted `lastSeenAt` before
   updating the active profile.
2. The welcome-back controller decides whether the absence qualifies for its
   richer overview.
3. The session summary always retains urgent maintenance or restart notices.
4. Mail, teleport-request, and death-marker counts are omitted from the compact
   summary when the richer welcome view already contains them.
5. Release and storage-health notices are restricted to their permissions.

This ordering gives one useful arrival experience instead of several services
repeating the same information.

## Home teleport

1. Resolve the named home by player UUID and stored world UUID.
2. Reject an unavailable world without guessing by world name.
3. Search for a safe landing near the destination without generating unsafe
   synchronous terrain reads.
4. Start the warm-up and cancel it on configured movement, damage, or state
   changes.
5. Close vulnerable container interaction before teleporting.
6. Apply cooldown only according to the completed teleport contract and give
   localized sound, particle, and message feedback.

## Player teleport request

1. Validate sender, target, privacy, duplicate request, and rate limits.
2. Store only a bounded, expiring session request.
3. Present accept and deny actions to the target.
4. Revalidate both players and destination safety at acceptance time.
5. Run the normal warm-up and cancellation path; permission bypass changes
   request or delay policy, not landing safety.

## Death and recovery

1. Capture the death world UUID and coordinates and persist the latest marker.
2. Attribute projectiles to their living shooter for custom death messages.
3. Restore the private floating guide after respawn and after reconnect while
   the marker remains valid.
4. Recreate or reposition the guide when chunk loading or fast travel removes
   its display entity.
5. Scale Nether and Overworld direction correctly without offering a death
   teleport.
6. Dismissal or expiry removes both persisted marker state and live display.

## Container lock

1. Resolve the complete container, including both halves of a double chest.
2. Check owner, trusted, deposit-only, public, and automation policy before the
   interaction changes inventory state.
3. Apply the same decision to opening, breaking, explosions, hoppers, and other
   indirect paths.
4. Record useful access history without turning denied interaction spam into
   unbounded data.
5. Require confirmation for destructive owner actions and persist one coherent
   lock aggregate.

## Maintenance and restart

1. Maintenance mode blocks new joins but does not unexpectedly remove current
   players.
2. Restart milestones are localized and visible without overwhelming chat.
3. New joins are blocked late enough to prevent a session starting during
   shutdown.
4. Persistence producers stop, online sessions close, queues drain, and the
   server shuts down cleanly.
5. An external process manager starts the server again; the plugin never
   pretends it can guarantee that step.

## Experience invariants

- Finnish clients receive Finnish automatically; all other locales receive
  English unless the player chooses an override.
- Every player action has one primary response surface. Sound and particles
  reinforce it but do not carry required information.
- Disabling optional dialogs, sounds, particles, or action bars preserves the
  complete workflow.
- Safety and access control are revalidated at the moment of effect, not only
  when a menu or command opens.
- Player UUIDs and world UUIDs are authoritative. Names are presentation and
  lookup aids, never persistent identity.
