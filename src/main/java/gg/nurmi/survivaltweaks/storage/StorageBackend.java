package gg.nurmi.survivaltweaks.storage;

import java.util.Locale;

public enum StorageBackend {
    SQLITE,
    MYSQL,
    POSTGRESQL;

    public static StorageBackend parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage backend must not be blank");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "storage backend must be sqlite, mysql, or postgresql",
                    exception
            );
        }
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
