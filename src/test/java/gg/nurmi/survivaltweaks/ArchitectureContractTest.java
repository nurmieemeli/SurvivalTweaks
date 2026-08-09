package gg.nurmi.survivaltweaks;

import gg.nurmi.survivaltweaks.object.VanillaGuideTopic;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureContractTest {

    private static final Pattern SESSION_MUTATION = Pattern.compile(
            "\\.(?:lastKnownName|lastSeenAt|playTimeTicks)\\([^)]"
    );
    private static final Pattern MESSAGE_KEY = Pattern.compile(
            "messages\\s*\\.\\s*(?:send|component)\\s*\\((?:[^,()\"]*,\\s*)?\"([a-z0-9][a-z0-9._-]*)\"",
            Pattern.DOTALL
    );

    @Test
    void onlineSessionMetadataHasOneRuntimeOwner() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (var sources = Files.walk(sourceRoot)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/storage/"))
                    .filter(path -> !path.getFileName().toString().equals("PlayerSessionService.java"))
                    .forEach(path -> findSessionMutations(sourceRoot, path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                "PlayerSessionService must remain the only runtime owner of session metadata: "
                        + String.join(", ", violations)
        );
    }

    /**
     * A key the catalogs do not define renders as "Missing message: &lt;key&gt;"
     * in game rather than failing anywhere a test would normally notice, so the
     * literals passed to the message API are checked against both catalogs.
     * Keys assembled at runtime end in '.' or '-' here and are skipped, since
     * only their prefix appears in the source.
     */
    @Test
    void everyMessageKeyUsedInSourceExistsInBothCatalogs() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        Set<String> english = catalogKeys("messages_en.yml");
        Set<String> finnish = catalogKeys("messages_fi.yml");
        List<String> missing = new ArrayList<>();

        try (var sources = Files.walk(sourceRoot)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                for (String key : messageKeys(path)) {
                    if (key.endsWith(".") || key.endsWith("-")) {
                        continue;
                    }
                    if (!english.contains(key)) {
                        missing.add(sourceRoot.relativize(path) + " -> messages_en.yml:" + key);
                    }
                    if (!finnish.contains(key)) {
                        missing.add(sourceRoot.relativize(path) + " -> messages_fi.yml:" + key);
                    }
                }
            });
        }

        assertTrue(missing.isEmpty(), "Undefined message key(s): " + String.join(", ", missing));
    }

    /**
     * Keys assembled at runtime are invisible to the scan above, because only
     * their prefix appears in the source. The enum-driven ones can still be
     * checked exhaustively, so a constant added without its message is caught
     * here rather than rendering "Missing message" in game.
     */
    @Test
    void everyEnumDrivenMessageKeyExistsInBothCatalogs() throws IOException {
        Set<String> english = catalogKeys("messages_en.yml");
        Set<String> finnish = catalogKeys("messages_fi.yml");
        List<String> expected = new ArrayList<>();
        for (VanillaGuideTopic topic : VanillaGuideTopic.values()) {
            expected.add("ui.vanilla-guide.topic." + topic.key());
        }
        for (NewPlayerSpawnService.RefillResult result : NewPlayerSpawnService.RefillResult.values()) {
            expected.add("admin.spawnpool.refill."
                    + result.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }

        List<String> missing = new ArrayList<>();
        for (String key : expected) {
            if (!english.contains(key)) {
                missing.add("messages_en.yml:" + key);
            }
            if (!finnish.contains(key)) {
                missing.add("messages_fi.yml:" + key);
            }
        }
        assertTrue(missing.isEmpty(), "Undefined message key(s): " + String.join(", ", missing));
    }

    /**
     * Journey objectives are driven by a list private to the menu, so the pair
     * is checked against itself: every objective needs the description shown
     * underneath it, in both languages.
     */
    @Test
    void everyJourneyObjectiveHasAMatchingDescription() throws IOException {
        for (String catalog : List.of("messages_en.yml", "messages_fi.yml")) {
            Set<String> keys = catalogKeys(catalog);
            Set<String> objectives = suffixes(keys, "ui.journey.objective.");
            Set<String> descriptions = suffixes(keys, "ui.journey.description.");
            assertFalse(objectives.isEmpty(), catalog + " defines no journey objectives");
            assertEquals(
                    objectives,
                    descriptions,
                    catalog + " journey objectives and descriptions do not match"
            );
        }
    }

    private Set<String> suffixes(Set<String> keys, String prefix) {
        Set<String> found = new LinkedHashSet<>();
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                found.add(key.substring(prefix.length()));
            }
        }
        return found;
    }

    private Set<String> messageKeys(Path path) {
        try {
            Set<String> keys = new LinkedHashSet<>();
            var matcher = MESSAGE_KEY.matcher(String.join("\n", Files.readAllLines(path)));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
            return keys;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect " + path, exception);
        }
    }

    private Set<String> catalogKeys(String resource) throws IOException {
        YamlConfiguration catalog = new YamlConfiguration();
        try {
            catalog.loadFromString(Files.readString(
                    Path.of("src", "main", "resources", resource),
                    StandardCharsets.UTF_8
            ));
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Could not parse " + resource, exception);
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String path : catalog.getKeys(true)) {
            if (!catalog.isConfigurationSection(path)) {
                keys.add(path);
            }
        }
        return keys;
    }

    private void findSessionMutations(Path sourceRoot, Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int index = 0; index < lines.size(); index++) {
                if (SESSION_MUTATION.matcher(lines.get(index)).find()) {
                    violations.add(sourceRoot.relativize(path) + ":" + (index + 1));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect " + path, exception);
        }
    }
}
