package gg.nurmi.survivaltweaks.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class StorageStateStore {

    private static final int VERSION = 1;

    private final Path stateFile;
    private final Path migrationFile;

    StorageStateStore(Path dataFolder) {
        Path normalized = Objects.requireNonNull(dataFolder, "dataFolder")
                .toAbsolutePath()
                .normalize();
        stateFile = normalized.resolve("storage-state.yml");
        migrationFile = normalized.resolve("storage-migration.yml");
    }

    Optional<State> loadState() throws IOException {
        if (Files.notExists(stateFile)) {
            return Optional.empty();
        }
        YamlConfiguration yaml = load(stateFile);
        int version = yaml.getInt("version", 0);
        if (version != VERSION) {
            throw new IOException("Unsupported storage-state.yml version " + version);
        }
        try {
            return Optional.of(new State(
                    StorageBackend.parse(require(yaml, "backend")),
                    require(yaml, "endpoint-fingerprint"),
                    UUID.fromString(require(yaml, "instance-id"))
            ));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid storage-state.yml", exception);
        }
    }

    void saveState(State state) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", VERSION);
        yaml.set("backend", state.backend().key());
        yaml.set("endpoint-fingerprint", state.endpointFingerprint());
        yaml.set("instance-id", state.instanceId().toString());
        save(stateFile, yaml);
    }

    Optional<Migration> loadMigration() throws IOException {
        if (Files.notExists(migrationFile)) {
            return Optional.empty();
        }
        YamlConfiguration yaml = load(migrationFile);
        try {
            return Optional.of(new Migration(
                    UUID.fromString(require(yaml, "id")),
                    StorageBackend.parse(require(yaml, "source")),
                    StorageBackend.parse(require(yaml, "target")),
                    require(yaml, "target-fingerprint")
            ));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid storage-migration.yml", exception);
        }
    }

    void stage(Migration migration) throws IOException {
        if (Files.exists(migrationFile)) {
            throw new IOException("A storage migration is already staged");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", migration.id().toString());
        yaml.set("source", migration.source().key());
        yaml.set("target", migration.target().key());
        yaml.set("target-fingerprint", migration.targetFingerprint());
        save(migrationFile, yaml);
    }

    void completeMigration() throws IOException {
        Files.deleteIfExists(migrationFile);
    }

    private YamlConfiguration load(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return yaml;
        } catch (Exception exception) {
            throw new IOException("Could not parse " + file.getFileName(), exception);
        }
    }

    private String require(YamlConfiguration yaml, String path) {
        String value = yaml.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + path);
        }
        return value.strip();
    }

    private void save(Path target, YamlConfiguration yaml) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".storage-state-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record State(StorageBackend backend, String endpointFingerprint, UUID instanceId) {
    }

    record Migration(
            UUID id,
            StorageBackend source,
            StorageBackend target,
            String targetFingerprint
    ) {
    }
}
