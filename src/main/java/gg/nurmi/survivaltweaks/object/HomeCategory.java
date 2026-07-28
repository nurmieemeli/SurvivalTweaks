package gg.nurmi.survivaltweaks.object;

public enum HomeCategory {
    BASE,
    FARM,
    RESOURCE,
    PUBLIC,
    OTHER;

    public HomeCategory next() {
        HomeCategory[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
