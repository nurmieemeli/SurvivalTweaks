package gg.nurmi.survivaltweaks.object;

public enum LanguagePreference {
    AUTO,
    FINNISH,
    ENGLISH;

    public LanguagePreference next() {
        LanguagePreference[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
