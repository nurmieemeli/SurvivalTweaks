package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.LanguagePreference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void pluralPicksTheSingularSiblingOnlyForExactlyOne() {
        assertEquals("ui.hub.mail-count-one", MessageService.plural("ui.hub.mail-count", 1));
        assertEquals("ui.hub.mail-count", MessageService.plural("ui.hub.mail-count", 0));
        assertEquals("ui.hub.mail-count", MessageService.plural("ui.hub.mail-count", 2));
        assertEquals("ui.hub.mail-count", MessageService.plural("ui.hub.mail-count", 97));
    }

    @Test
    void failedActionCanOfferAClickableCorrection() {
        MessageService actionable = new MessageService(
                Map.of(
                        "home.none", "No homes",
                        "recovery.create-home", "[Create home]"
                ),
                Map.of(),
                Logger.getAnonymousLogger()
        );
        Player player = player(Locale.US);

        actionable.send(player, "home.none");

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertEquals(
                ClickEvent.suggestCommand("/sethome "),
                sent.getValue().children().getLast().clickEvent()
        );
    }

    private Player player(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }
}
