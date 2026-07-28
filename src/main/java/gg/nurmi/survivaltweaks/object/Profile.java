package gg.nurmi.survivaltweaks.object;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class Profile {

    private final UUID uniqueId;
    private final Map<String, Home> homes = new LinkedHashMap<>();
    private PlayerPreferences preferences = PlayerPreferences.DEFAULTS;
    private final LinkedHashSet<OnboardingHint> seenHints = new LinkedHashSet<>();
    private final ArrayList<PlayerNotification> notifications = new ArrayList<>();
    private final LinkedHashSet<UUID> blockedMailSenders = new LinkedHashSet<>();
    private String lastKnownName = "";
    private Instant lastSeenAt;
    private long playTimeTicks;
    private boolean migrationRequired;

    public Profile(UUID uniqueId) {
        this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public List<Home> homes() {
        return List.copyOf(homes.values());
    }

    public Optional<Home> home(String name) {
        return Optional.ofNullable(homes.get(key(name)));
    }

    public boolean addHome(Home home) {
        Objects.requireNonNull(home, "home");
        return homes.putIfAbsent(key(home.name()), home) == null;
    }

    public boolean removeHome(String name) {
        return homes.remove(key(name)) != null;
    }

    public boolean updateHome(Home home) {
        Objects.requireNonNull(home, "home");
        String key = key(home.name());
        if (!homes.containsKey(key)) {
            return false;
        }
        homes.put(key, home);
        return true;
    }

    public boolean renameHome(String currentName, String updatedName) {
        String currentKey = key(currentName);
        String updatedKey = key(updatedName);
        Home current = homes.get(currentKey);
        if (current == null || (!currentKey.equals(updatedKey) && homes.containsKey(updatedKey))) {
            return false;
        }
        LinkedHashMap<String, Home> reordered = new LinkedHashMap<>();
        homes.forEach((storedKey, home) -> {
            if (storedKey.equals(currentKey)) {
                reordered.put(updatedKey, current.withName(updatedName));
            } else {
                reordered.put(storedKey, home);
            }
        });
        homes.clear();
        homes.putAll(reordered);
        return true;
    }

    public PlayerPreferences preferences() {
        return preferences;
    }

    public void preferences(PlayerPreferences updatedPreferences) {
        preferences = Objects.requireNonNull(updatedPreferences, "updatedPreferences");
    }

    public boolean hintSeen(OnboardingHint hint) {
        return seenHints.contains(hint);
    }

    public boolean markHintSeen(OnboardingHint hint) {
        return seenHints.add(Objects.requireNonNull(hint, "hint"));
    }

    public void seenHints(java.util.Collection<OnboardingHint> hints) {
        seenHints.clear();
        seenHints.addAll(hints);
    }

    public Set<OnboardingHint> seenHints() {
        return Set.copyOf(seenHints);
    }

    public List<PlayerNotification> notifications() {
        return List.copyOf(notifications);
    }

    public void notifications(java.util.Collection<PlayerNotification> loaded) {
        notifications.clear();
        notifications.addAll(loaded);
    }

    public void addNotification(PlayerNotification notification, int maximum) {
        notifications.addFirst(Objects.requireNonNull(notification, "notification"));
        while (notifications.size() > maximum) {
            notifications.removeLast();
        }
    }

    public boolean markNotificationRead(UUID notificationId) {
        for (int index = 0; index < notifications.size(); index++) {
            PlayerNotification notification = notifications.get(index);
            if (notification.id().equals(notificationId) && !notification.read()) {
                notifications.set(index, notification.withRead(true));
                return true;
            }
        }
        return false;
    }

    public boolean removeNotification(UUID notificationId) {
        return notifications.removeIf(notification -> notification.id().equals(notificationId));
    }

    public int clearReadNotifications() {
        int before = notifications.size();
        notifications.removeIf(PlayerNotification::read);
        return before - notifications.size();
    }

    public int clearReadMail() {
        int before = notifications.size();
        notifications.removeIf(notification ->
                notification.type() == NotificationType.MAIL && notification.read());
        return before - notifications.size();
    }

    public long unreadNotificationCount() {
        return notifications.stream().filter(notification -> !notification.read()).count();
    }

    public long unreadMailCount() {
        return notifications.stream()
                .filter(notification -> notification.type() == NotificationType.MAIL)
                .filter(notification -> !notification.read())
                .count();
    }

    public String lastKnownName() {
        return lastKnownName;
    }

    public void lastKnownName(String updatedName) {
        lastKnownName = updatedName == null ? "" : updatedName.strip();
    }

    public Optional<Instant> lastSeenAt() {
        return Optional.ofNullable(lastSeenAt);
    }

    public void lastSeenAt(Instant updatedLastSeen) {
        lastSeenAt = updatedLastSeen;
    }

    public long playTimeTicks() {
        return playTimeTicks;
    }

    public void playTimeTicks(long updatedPlayTimeTicks) {
        playTimeTicks = Math.max(0, updatedPlayTimeTicks);
    }

    public Set<UUID> blockedMailSenders() {
        return Set.copyOf(blockedMailSenders);
    }

    public void blockedMailSenders(java.util.Collection<UUID> playerIds) {
        blockedMailSenders.clear();
        blockedMailSenders.addAll(playerIds);
    }

    public boolean blockMailFrom(UUID playerId) {
        return blockedMailSenders.add(Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean unblockMailFrom(UUID playerId) {
        return blockedMailSenders.remove(playerId);
    }

    public boolean blocksMailFrom(UUID playerId) {
        return blockedMailSenders.contains(playerId);
    }

    public ProfileSnapshot snapshot() {
        return new ProfileSnapshot(
                uniqueId,
                homes(),
                preferences,
                seenHints,
                notifications,
                lastKnownName,
                lastSeenAt,
                playTimeTicks,
                blockedMailSenders
        );
    }

    public boolean migrationRequired() {
        return migrationRequired;
    }

    public void requireMigration() {
        migrationRequired = true;
    }

    private static String key(String name) {
        Objects.requireNonNull(name, "name");
        return name.toLowerCase(Locale.ROOT);
    }
}
