package gg.nurmi.survivaltweaks.storage;

import gg.nurmi.survivaltweaks.object.BlockKey;
import gg.nurmi.survivaltweaks.object.ContainerLockSnapshot;
import gg.nurmi.survivaltweaks.object.DeathMarker;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnAssignment;
import gg.nurmi.survivaltweaks.object.NewPlayerSpawnLocation;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.object.ProfileSnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

public final class StorageChecksum {

    private final MessageDigest digest;

    private StorageChecksum() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String calculate(StorageSnapshot snapshot) {
        StorageChecksum checksum = new StorageChecksum();
        snapshot.profiles().stream()
                .sorted(Comparator.comparing(ProfileSnapshot::uniqueId))
                .forEach(checksum::profile);
        snapshot.locks().stream()
                .sorted(Comparator.comparing(ContainerLockSnapshot::id))
                .forEach(checksum::lock);
        snapshot.deathMarkers().stream()
                .sorted(Comparator.comparing(DeathMarker::playerId))
                .forEach(checksum::deathMarker);
        snapshot.newPlayerSpawns().available().forEach(location ->
                checksum.location("available", null, false, location));
        snapshot.newPlayerSpawns().assignments().values().stream()
                .sorted(Comparator.comparing(NewPlayerSpawnAssignment::playerId))
                .forEach(assignment -> checksum.location(
                        "assignment",
                        assignment.playerId(),
                        assignment.completed(),
                        assignment.location()
                ));
        snapshot.newPlayerSpawns().retired().forEach(location ->
                checksum.location("retired", null, false, location));
        snapshot.newPlayerSpawns().awaitingReplacement().stream()
                .sorted()
                .forEach(playerId -> {
                    checksum.put("replacement");
                    checksum.put(playerId);
                });
        return HexFormat.of().formatHex(checksum.digest.digest());
    }

    private void profile(ProfileSnapshot profile) {
        put("profile");
        put(profile.uniqueId());
        PlayerPreferences preferences = profile.preferences();
        put(preferences.soundsEnabled());
        put(preferences.particlesEnabled());
        put(preferences.dialogsEnabled());
        put(preferences.actionBarEnabled());
        put(preferences.reducedEffects());
        put(preferences.playerListEnabled());
        put(preferences.mentionNotificationsEnabled());
        put(preferences.journeyGuidanceEnabled());
        put(preferences.publicProfileEnabled());
        put(preferences.mailEnabled());
        put(preferences.language());
        put(profile.lastKnownName());
        putInstant(profile.lastSeenAt());
        put(profile.playTimeTicks());
        profile.homes().forEach(home -> home(profile.uniqueId(), home));
        profile.seenHints().stream().sorted(Comparator.comparing(Enum::name)).forEach(this::put);
        profile.notifications().forEach(notification ->
                notification(profile.uniqueId(), notification));
        profile.blockedMailSenders().stream().sorted().forEach(this::put);
    }

    private void home(UUID playerId, Home home) {
        put("home");
        put(playerId);
        put(home.name());
        put(home.worldId());
        put(home.worldName());
        put(Double.toHexString(home.x()));
        put(Double.toHexString(home.y()));
        put(Double.toHexString(home.z()));
        put(Float.toHexString(home.yaw()));
        put(Float.toHexString(home.pitch()));
        put(home.icon().getKey().asString());
        put(home.description());
        put(home.favorite());
        put(home.order());
        put(home.category());
        put(home.arrivalStyle());
        home.sharedWith().stream().sorted().forEach(this::put);
    }

    private void notification(UUID playerId, PlayerNotification notification) {
        put("notification");
        put(playerId);
        put(notification.id());
        put(notification.type());
        putInstant(notification.createdAt());
        put(notification.actorId());
        put(notification.actor());
        put(notification.detail());
        put(notification.read());
    }

    private void lock(ContainerLockSnapshot lock) {
        put("lock");
        put(lock.id());
        put(lock.ownerId());
        put(lock.name());
        put(lock.accessMode());
        put(lock.automationAllowed());
        lock.blocks().stream()
                .sorted(Comparator.comparing(BlockKey::worldId)
                        .thenComparingInt(BlockKey::x)
                        .thenComparingInt(BlockKey::y)
                        .thenComparingInt(BlockKey::z))
                .forEach(block -> {
                    put(block.worldId());
                    put(block.x());
                    put(block.y());
                    put(block.z());
                });
        lock.trustedPlayers().stream().sorted().forEach(this::put);
    }

    private void deathMarker(DeathMarker marker) {
        put("death");
        put(marker.playerId());
        put(marker.worldId());
        put(marker.worldName());
        put(Double.toHexString(marker.x()));
        put(Double.toHexString(marker.y()));
        put(Double.toHexString(marker.z()));
        putInstant(marker.createdAt());
        putInstant(marker.expiresAt());
        put(marker.cause());
    }

    private void location(
            String kind,
            UUID playerId,
            boolean completed,
            NewPlayerSpawnLocation location
    ) {
        put("spawn");
        put(kind);
        put(playerId);
        put(completed);
        put(location.worldId());
        put(location.worldName());
        put(location.x());
        put(location.y());
        put(location.z());
        put(Float.toHexString(location.yaw()));
    }

    private void put(Object value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private void putInstant(java.time.Instant value) {
        put(value == null ? null : value.toEpochMilli());
    }
}
