package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.LanguagePreference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisplayFormatTest {

    private final MessageService messages = catalogs();

    @Test
    void playtimeCollapsesIntoDaysPastTwoDays() {
        Player viewer = player(Locale.US);

        assertEquals("0h 0m", plain(DisplayFormat.playtime(messages, viewer, 0)));
        assertEquals("1h 30m", plain(DisplayFormat.playtime(messages, viewer, 20L * 60L * 90L)));
        assertEquals("2d 2h", plain(DisplayFormat.playtime(messages, viewer, 20L * 60L * 60L * 50L)));
    }

    @Test
    void hoursMinutesNeverCollapsesIntoDays() {
        Player viewer = player(Locale.US);

        assertEquals("2h 5m", plain(DisplayFormat.hoursMinutes(messages, viewer, 150_000)));
        assertEquals("50h 0m", plain(DisplayFormat.hoursMinutes(messages, viewer, 20L * 60L * 60L * 50L)));
    }

    @Test
    void awayReducesToItsMostSignificantUnit() {
        Player viewer = player(Locale.US);

        assertEquals("3d", plain(DisplayFormat.away(messages, viewer, Duration.ofHours(72))));
        assertEquals("5h", plain(DisplayFormat.away(messages, viewer, Duration.ofHours(5))));
        assertEquals("1m", plain(DisplayFormat.away(messages, viewer, Duration.ZERO)));
    }

    @Test
    void distanceSwitchesToKilometresPastAThousandBlocks() {
        Player viewer = player(Locale.US);

        assertEquals("999 m", plain(DisplayFormat.distance(messages, viewer, 99_900)));
        assertEquals("1.2 km", plain(DisplayFormat.distance(messages, viewer, 123_450)));
    }

    @Test
    void damageReportsVanillaTenthsOfAHeart() {
        Player viewer = player(Locale.US);

        assertEquals("12.5", DisplayFormat.damage(messages, viewer, 125));
        assertEquals("0.0", DisplayFormat.damage(messages, viewer, -1));
    }

    @Test
    void everyUnitFollowsTheReadersLanguage() {
        Player finnish = player(Locale.forLanguageTag("fi-FI"));

        assertEquals("1 t 30 min", plain(DisplayFormat.playtime(messages, finnish, 20L * 60L * 90L)));
        assertEquals("2 pv 2 t", plain(DisplayFormat.playtime(messages, finnish, 20L * 60L * 60L * 50L)));
        assertEquals("2 t 5 min", plain(DisplayFormat.hoursMinutes(messages, finnish, 150_000)));
        assertEquals("3 pv", plain(DisplayFormat.away(messages, finnish, Duration.ofHours(72))));
        assertEquals("5 t", plain(DisplayFormat.away(messages, finnish, Duration.ofHours(5))));
        assertEquals("1 min", plain(DisplayFormat.away(messages, finnish, Duration.ZERO)));
        assertEquals("999 m", plain(DisplayFormat.distance(messages, finnish, 99_900)));
    }

    @Test
    void decimalsUseTheSeparatorOfTheLanguageTheReaderSees() {
        Player finnish = player(Locale.forLanguageTag("fi-FI"));

        assertEquals("12,5", DisplayFormat.damage(messages, finnish, 125));
        assertEquals("1,2 km", plain(DisplayFormat.distance(messages, finnish, 123_450)));
        assertEquals("19,85", DisplayFormat.decimal(messages, finnish, 19.85, 2));
        assertEquals("19.85", DisplayFormat.decimal(messages, player(Locale.US), 19.85, 2));
    }

    @Test
    void anExplicitLanguageChoiceDrivesTheSeparatorAgainstTheClientLocale() {
        MessageService overridden = catalogs();
        overridden.languagePreference(id -> LanguagePreference.FINNISH);
        Player viewer = player(Locale.US);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());

        assertEquals("12,5", DisplayFormat.damage(overridden, viewer, 125));
        assertEquals("1,2 km", plain(DisplayFormat.distance(overridden, viewer, 123_450)));
    }

    @Test
    void consoleAndOtherNonPlayerAudiencesStayOnEnglishSeparators() {
        assertEquals("19.85", DisplayFormat.decimal(messages, null, 19.85, 2));
    }

    private static MessageService catalogs() {
        return new MessageService(
                Map.of(
                        "format.duration.days-hours", "<days>d <hours>h",
                        "format.duration.hours-minutes", "<hours>h <minutes>m",
                        "format.duration.days", "<days>d",
                        "format.duration.hours", "<hours>h",
                        "format.duration.minutes", "<minutes>m",
                        "format.distance.kilometres", "<value> km",
                        "format.distance.metres", "<value> m"
                ),
                Map.of(
                        "format.duration.days-hours", "<days> pv <hours> t",
                        "format.duration.hours-minutes", "<hours> t <minutes> min",
                        "format.duration.days", "<days> pv",
                        "format.duration.hours", "<hours> t",
                        "format.duration.minutes", "<minutes> min",
                        "format.distance.kilometres", "<value> km",
                        "format.distance.metres", "<value> m"
                ),
                Logger.getAnonymousLogger()
        );
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private Player player(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }
}
