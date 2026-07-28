package gg.nurmi.survivaltweaks.object;

public enum HomeArrivalStyle {
    DEFAULT("teleport-complete"),
    SPARKLE("home-arrival-sparkle"),
    PORTAL("home-arrival-portal"),
    GENTLE("home-arrival-gentle"),
    NONE("");

    private final String cue;

    HomeArrivalStyle(String cue) {
        this.cue = cue;
    }

    public String cue() {
        return cue;
    }

    public HomeArrivalStyle next() {
        HomeArrivalStyle[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
