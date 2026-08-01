package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.storage.SqlStorage;
import gg.nurmi.survivaltweaks.storage.StorageManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OperationalHealthService {

    public static final String NOTIFY_PERMISSION = "survivaltweaks.health-notify";
    private static final long JOIN_DELAY_TICKS = 80L;
    private static final long SLOW_STORAGE_MILLIS = 1_000L;
    private static final long STALLED_WRITE_MILLIS = 30_000L;
    private static final Duration BACKUP_MAX_AGE = Duration.ofHours(48);

    private final JavaPlugin plugin;
    private final StorageManager storage;
    private final PortableExportService exports;
    private final PersistenceMonitor persistence;
    private final BackupService backups;
    private final MessageService messages;

    public OperationalHealthService(
            JavaPlugin plugin,
            StorageManager storage,
            PortableExportService exports,
            PersistenceMonitor persistence,
            BackupService backups,
            MessageService messages
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public void playerJoined(Player player) {
        if (!player.hasPermission(NOTIFY_PERMISSION)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            Report report = inspect();
            if (report.warnings().isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                messages.send(
                        player,
                        "admin.health.header",
                        Placeholder.unparsed("count", Integer.toString(report.warnings().size()))
                );
                report.warnings().forEach(warning -> messages.send(
                        player,
                        "admin.health." + warning.key(),
                        Placeholder.unparsed("detail", warning.detail())
                ));
            });
        }, JOIN_DELAY_TICKS);
    }

    Report inspect() {
        List<Warning> warnings = new ArrayList<>();
        Instant now = Instant.now();
        SqlStorage.Status database = storage.status();
        if (!database.healthy()) {
            warnings.add(new Warning("storage-unavailable", database.problem()));
        } else {
            if (database.latencyMillis() >= SLOW_STORAGE_MILLIS) {
                warnings.add(new Warning("storage-slow", database.latencyMillis() + " ms"));
            }
            if (database.waitingThreads() > 0) {
                warnings.add(new Warning(
                        "storage-waiting",
                        Integer.toString(database.waitingThreads())
                ));
            }
        }

        PersistenceMonitor.Snapshot writes = persistence.snapshot();
        if (writes.oldestQueueMillis() >= STALLED_WRITE_MILLIS) {
            warnings.add(new Warning(
                    "writes-stalled",
                    writes.pending() + " / " + writes.oldestQueueMillis() / 1_000L + " s"
            ));
        }
        if (writes.lastFailure() != null
                && (writes.lastSuccess() == null
                || writes.lastFailure().isAfter(writes.lastSuccess()))) {
            warnings.add(new Warning("writes-failed", writes.lastFailureReason()));
        }

        PortableExportService.Status export = exports.status();
        if (export.lastFailure() != null
                && (export.lastSuccess() == null
                || export.lastFailure().isAfter(export.lastSuccess()))) {
            warnings.add(new Warning("export-failed", export.lastFailureReason()));
        } else if (export.overdue(now)) {
            warnings.add(new Warning("export-overdue", export.interval().toHours() + " h"));
        }

        try {
            List<BackupService.ArchiveInfo> archives = backups.archives();
            if (archives.isEmpty()) {
                warnings.add(new Warning("backup-missing", ""));
            } else {
                BackupService.ArchiveInfo newest = archives.getFirst();
                Duration age = Duration.between(newest.modified(), now);
                if (age.compareTo(BACKUP_MAX_AGE) > 0) {
                    warnings.add(new Warning("backup-overdue", age.toHours() + " h"));
                }
            }
        } catch (IOException exception) {
            warnings.add(new Warning(
                    "backup-failed",
                    Objects.requireNonNullElse(exception.getMessage(), "I/O error")
            ));
        }
        return new Report(List.copyOf(warnings));
    }

    record Report(List<Warning> warnings) {
    }

    record Warning(String key, String detail) {
    }
}
