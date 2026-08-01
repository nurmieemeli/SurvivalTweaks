package gg.nurmi.survivaltweaks;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureContractTest {

    private static final Pattern SESSION_MUTATION = Pattern.compile(
            "\\.(?:lastKnownName|lastSeenAt|playTimeTicks)\\([^)]"
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
