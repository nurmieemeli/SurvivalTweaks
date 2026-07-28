package gg.nurmi.survivaltweaks.object;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public enum CustomDeathCause {
    FALL(DamageCause.FALL, DamageCause.FLY_INTO_WALL),
    FIRE(
            DamageCause.FIRE,
            DamageCause.FIRE_TICK,
            DamageCause.LAVA,
            DamageCause.HOT_FLOOR,
            DamageCause.CAMPFIRE
    ),
    DROWNING(DamageCause.DROWNING),
    SUFFOCATION(DamageCause.SUFFOCATION),
    VOID(DamageCause.VOID),
    FREEZING(DamageCause.FREEZE),
    STARVATION(DamageCause.STARVATION),
    LIGHTNING(DamageCause.LIGHTNING),
    CONTACT(DamageCause.CONTACT),
    EXPLOSION(DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION),
    WORLD_BORDER(DamageCause.WORLD_BORDER),
    CRAMMING(DamageCause.CRAMMING);

    private final String key;
    private final Set<DamageCause> damageCauses;

    CustomDeathCause(DamageCause... damageCauses) {
        this.key = name().toLowerCase(Locale.ROOT).replace('_', '-');
        this.damageCauses = Set.of(damageCauses);
    }

    public String key() {
        return key;
    }

    public String messageKey(int standardVariant, boolean rare) {
        if (rare) {
            return "death-messages." + key + ".rare";
        }
        if (standardVariant < 1 || standardVariant > 2) {
            throw new IllegalArgumentException("Standard death-message variant must be 1 or 2");
        }
        return "death-messages." + key + "." + standardVariant;
    }

    public static Optional<CustomDeathCause> from(DamageCause damageCause) {
        if (damageCause == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(cause -> cause.damageCauses.contains(damageCause))
                .findFirst();
    }

    public static Set<String> keys() {
        return Arrays.stream(values())
                .map(CustomDeathCause::key)
                .collect(Collectors.toUnmodifiableSet());
    }
}
