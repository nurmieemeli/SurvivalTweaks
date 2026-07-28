package gg.nurmi.survivaltweaks.config;

import java.util.Objects;

public final class SettingsService {

    private volatile PluginSettings current;

    public SettingsService(PluginSettings initial) {
        this.current = Objects.requireNonNull(initial, "initial");
    }

    public PluginSettings current() {
        return current;
    }

    public void apply(PluginSettings settings) {
        this.current = Objects.requireNonNull(settings, "settings");
    }
}
