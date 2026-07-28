package gg.nurmi.survivaltweaks.object;

public enum LockAccessMode {
    TRUSTED,
    DEPOSIT_ONLY,
    PUBLIC;

    public LockAccessMode next() {
        LockAccessMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
