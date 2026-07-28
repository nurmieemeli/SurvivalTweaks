package gg.nurmi.survivaltweaks.object;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public enum VanillaGuideTopic {
    NETHER_COORDINATES(OnboardingHint.VANILLA_NETHER_COORDINATES),
    SLEEP_RULES(OnboardingHint.VANILLA_SLEEP_RULES),
    VILLAGER_CURING(OnboardingHint.VANILLA_VILLAGER_CURING),
    RESPAWN_ANCHORS(OnboardingHint.VANILLA_RESPAWN_ANCHORS),
    LODESTONES(OnboardingHint.VANILLA_LODESTONES),
    ANVILS(OnboardingHint.VANILLA_ANVILS),
    ENCHANTING(OnboardingHint.VANILLA_ENCHANTING);

    private final OnboardingHint hint;
    private final String key;

    VanillaGuideTopic(OnboardingHint hint) {
        this.hint = hint;
        this.key = name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public OnboardingHint hint() {
        return hint;
    }

    public String key() {
        return key;
    }

    public static Optional<VanillaGuideTopic> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(topic -> topic.key.equalsIgnoreCase(key.strip()))
                .findFirst();
    }

    public static Set<String> keys() {
        return Arrays.stream(values()).map(VanillaGuideTopic::key).collect(Collectors.toUnmodifiableSet());
    }
}
