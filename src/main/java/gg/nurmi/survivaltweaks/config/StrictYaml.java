package gg.nurmi.survivaltweaks.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class StrictYaml {
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EVENTS = 250_000;
    private static final int MAX_DEPTH = 100;
    private static final int MAX_ALIAS_EXPANSION = 25_000;
    private static final int MAX_PATH_LENGTH = 2_048;

    private StrictYaml() {
    }

    public static YamlConfiguration load(Path file) throws IOException, InvalidConfigurationException {
        return parse(read(file), file.getFileName().toString());
    }

    public static byte[] read(Path file) throws IOException, InvalidConfigurationException {
        try (var input = Files.newInputStream(file)) {
            byte[] content = input.readNBytes(MAX_FILE_BYTES + 1);
            if (content.length > MAX_FILE_BYTES) {
                throw invalid("YAML file exceeds the 16 MiB safety limit: ", file.getFileName().toString(), null);
            }
            return content;
        }
    }

    public static YamlConfiguration parse(byte[] source, String filename) throws IOException, InvalidConfigurationException {
        String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString();
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        options.setCodePointLimit(MAX_FILE_BYTES);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(options);
        try {
            int remaining = MAX_EVENTS;
            for (var event : yaml.parse(new StringReader(content))) {
                if (--remaining < 0) {
                    throw invalid("YAML document exceeds the parser event limit in ", filename, event.getStartMark());
                }
            }
            Node root = yaml.compose(new StringReader(content));
            if (root != null) {
                checkAliasExpansion(root, Collections.newSetFromMap(new IdentityHashMap<>()),
                        MAX_ALIAS_EXPANSION, 0, filename);
            }
            if (yaml.load(content) instanceof Map<?, ?> mapping) {
                checkPaths(mapping, "", new HashMap<>(), filename);
            }
        } catch (DuplicateKeyException exception) {
            throw invalid("Duplicate YAML key in ", filename, exception.getProblemMark());
        } catch (MarkedYAMLException exception) {
            throw invalid("Could not parse ", filename, exception.getProblemMark());
        } catch (YAMLException exception) {
            throw invalid("Could not parse ", filename, null);
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(content);
        } catch (InvalidConfigurationException exception) {
            Mark mark = exception.getCause() instanceof MarkedYAMLException marked ? marked.getProblemMark() : null;
            throw invalid("Could not parse ", filename, mark);
        }
        return configuration;
    }

    private static int checkAliasExpansion(Node node, Set<Node> seen, int remaining, int depth, String filename)
            throws InvalidConfigurationException {
        // Bukkit creates a separate section for each alias reference, including nested references.
        if (depth > MAX_DEPTH || (!seen.add(node) && --remaining < 0)) {
            throw invalid("YAML alias expansion exceeds the safety limit in ", filename, node.getStartMark());
        }
        if (node instanceof MappingNode mapping) {
            for (var entry : mapping.getValue()) {
                remaining = checkAliasExpansion(entry.getKeyNode(), seen, remaining, depth + 1, filename);
                remaining = checkAliasExpansion(entry.getValueNode(), seen, remaining, depth + 1, filename);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node entry : sequence.getValue()) {
                remaining = checkAliasExpansion(entry, seen, remaining, depth + 1, filename);
            }
        }
        return remaining;
    }

    private static void checkPaths(Map<?, ?> mapping, String parent, Map<String, Boolean> paths, String filename)
            throws InvalidConfigurationException {
        for (var entry : mapping.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = parent + key;
            if (path.length() > MAX_PATH_LENGTH) {
                throw invalid("YAML key path exceeds the safety limit in ", filename, null);
            }
            for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
                if (Boolean.FALSE.equals(paths.putIfAbsent(parent + key.substring(0, dot), true))) {
                    throw invalid("Conflicting YAML keys in ", filename, null);
                }
            }
            Object value = entry.getValue();
            if (paths.putIfAbsent(path, value instanceof Map<?, ?>) != null) {
                throw invalid("Conflicting YAML keys in ", filename, null);
            }
            if (value instanceof Map<?, ?> section) {
                checkPaths(section, path + ".", paths, filename);
            }
        }
    }

    private static InvalidConfigurationException invalid(String reason, String filename, Mark mark) {
        // SnakeYAML exceptions can include source lines containing credentials.
        String location = mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
        return new InvalidConfigurationException(reason + filename + location);
    }
}
