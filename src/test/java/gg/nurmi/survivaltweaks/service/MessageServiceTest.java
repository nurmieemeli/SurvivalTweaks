package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.LanguagePreference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    private final MessageService messages = new MessageService(
            Map.of(
                    "greeting", "Hello",
                    "english-only", "English fallback"
            ),
            Map.of("greeting", "Hei"),
            Logger.getAnonymousLogger()
    );

    @Test
    void finnishLanguageUsesFinnishCatalog() {
        Player player = player(Locale.forLanguageTag("fi-FI"));

        assertEquals(Component.text("Hei"), messages.component(player, "greeting"));
    }

    @Test
    void everyNonFinnishLanguageUsesEnglishCatalog() {
        assertEquals(
                Component.text("Hello"),
                messages.component(player(Locale.forLanguageTag("sv-SE")), "greeting")
        );
        assertEquals(
                Component.text("Hello"),
                messages.component(player(Locale.US), "greeting")
        );
    }

    @Test
    void nonPlayerAudiencesAndMissingFinnishKeysFallBackToEnglish() {
        assertEquals(
                Component.text("Hello"),
                messages.component(mock(Audience.class), "greeting")
        );
        assertEquals(
                Component.text("English fallback"),
                messages.component(player(Locale.forLanguageTag("fi-FI")), "english-only")
        );
    }

    @Test
    void localeChangesAreObservedWithoutAPluginCache() {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(Locale.forLanguageTag("fi-FI"), Locale.US);

        assertEquals(Component.text("Hei"), messages.component(player, "greeting"));
        assertEquals(Component.text("Hello"), messages.component(player, "greeting"));
    }

    @Test
    void explicitLanguagePreferenceOverridesTheClientLocale() {
        UUID playerId = UUID.randomUUID();
        Player player = player(Locale.US);
        when(player.getUniqueId()).thenReturn(playerId);
        messages.languagePreference(id -> LanguagePreference.FINNISH);

        assertEquals(Component.text("Hei"), messages.component(player, "greeting"));
    }

    private Player player(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }
}
