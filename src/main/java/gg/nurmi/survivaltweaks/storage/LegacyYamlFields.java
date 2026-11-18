package gg.nurmi.survivaltweaks.storage;

import java.util.Map;

final class LegacyYamlFields {
    private LegacyYamlFields() {
    }

    static String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing text field '" + key + "'");
        }
        return value.toString();
    }

    static Number number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing numeric field '" + key + "'");
        }
        return number;
    }
}
