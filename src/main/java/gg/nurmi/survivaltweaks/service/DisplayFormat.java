package gg.nurmi.survivaltweaks.service;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.time.Duration;

/**
 * Renders durations and distances through the language catalogs so unit
 * suffixes follow the reader's language instead of being baked into Java.
 */
public final class DisplayFormat {

    private static final long TICKS_PER_MINUTE = 20L * 60L;

    private DisplayFormat() {
    }

    /** Playtime that collapses into days once it passes two days. */
    public static Component playtime(MessageService messages, Audience viewer, long ticks) {
        long hours = minutes(ticks) / 60L;
        if (hours >= 48) {
            return messages.component(
                    viewer,
                    "format.duration.days-hours",
                    Placeholder.unparsed("days", Long.toString(hours / 24L)),
                    Placeholder.unparsed("hours", Long.toString(hours % 24L))
            );
        }
        return hoursMinutes(messages, viewer, ticks);
    }

    /** Playtime that always reads as hours and minutes. */
    public static Component hoursMinutes(MessageService messages, Audience viewer, long ticks) {
        long totalMinutes = minutes(ticks);
        return messages.component(
                viewer,
                "format.duration.hours-minutes",
                Placeholder.unparsed("hours", Long.toString(totalMinutes / 60L)),
                Placeholder.unparsed("minutes", Long.toString(totalMinutes % 60L))
        );
    }

    /** Time away from the server, reduced to its most significant unit. */
    public static Component away(MessageService messages, Audience viewer, Duration away) {
        long hours = Math.max(0, away.toHours());
        if (hours >= 48) {
            return messages.component(
                    viewer,
                    "format.duration.days",
                    Placeholder.unparsed("days", Long.toString(hours / 24L))
            );
        }
        if (hours >= 1) {
            return messages.component(
                    viewer,
                    "format.duration.hours",
                    Placeholder.unparsed("hours", Long.toString(hours))
            );
        }
        return messages.component(
                viewer,
                "format.duration.minutes",
                Placeholder.unparsed("minutes", Long.toString(Math.max(1, away.toMinutes())))
        );
    }

    /** Travelled distance, switching to kilometres past a thousand blocks. */
    public static Component distance(MessageService messages, Audience viewer, long centimetres) {
        double blocks = Math.max(0, centimetres) / 100.0;
        if (blocks >= 1_000.0) {
            return messages.component(
                    viewer,
                    "format.distance.kilometres",
                    Placeholder.unparsed("value", decimal(messages, viewer, blocks / 1_000.0, 1))
            );
        }
        return messages.component(
                viewer,
                "format.distance.metres",
                Placeholder.unparsed("value", Long.toString(Math.round(blocks)))
        );
    }

    /** Vanilla damage statistics, which are recorded in tenths of a heart. */
    public static String damage(MessageService messages, Audience viewer, long rawDamage) {
        return decimal(messages, viewer, Math.max(0, rawDamage) / 10.0, 1);
    }

    /**
     * A decimal number using the separator of the language the audience reads,
     * so Finnish readers see {@code 1,2} rather than {@code 1.2}.
     */
    public static String decimal(
            MessageService messages,
            Audience viewer,
            double value,
            int fractionDigits
    ) {
        return String.format(messages.locale(viewer), "%." + fractionDigits + "f", value);
    }

    private static long minutes(long ticks) {
        return Math.max(0, ticks) / TICKS_PER_MINUTE;
    }
}
