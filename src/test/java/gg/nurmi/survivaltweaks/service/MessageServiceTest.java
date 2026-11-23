package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.LanguagePreference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void startupUsesBundledMessagesAndPreservesInvalidCatalogs(@TempDir Path directory) throws IOException {
        StringBuilder english = new StringBuilder("a0: &a0 {leaf: value}\n");
        for (int level = 1; level < 24; level++) {
            english.append("a%d: &a%d {left: *a%d, right: *a%d}\n".formatted(level, level, level - 1, level - 1));
        }
        String finnish = "player-only: false\n";
        Path englishFile = directory.resolve("messages_en.yml");
        Path finnishFile = directory.resolve("messages_fi.yml");
        Files.writeString(englishFile, english);
        Files.writeString(finnishFile, finnish);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("messages_en.yml")).thenAnswer(ignored -> getClass().getResourceAsStream("/messages_en.yml"));
        when(plugin.getResource("messages_fi.yml")).thenAnswer(ignored -> getClass().getResourceAsStream("/messages_fi.yml"));
        YamlConfiguration config = new YamlConfiguration();
        config.set("messages.player-only", "legacy value");

        MessageService service = new MessageService(plugin, config, Logger.getAnonymousLogger());

        assertEquals("This command can only be used by a player.", service.plain(null, "player-only"));
        assertEquals("Tätä komentoa voi käyttää vain pelaajana.", service.plain(player(Locale.forLanguageTag("fi-FI")), "player-only"));
        assertEquals(english.toString(), Files.readString(englishFile));
        assertEquals(finnish, Files.readString(finnishFile));
        assertFalse(service.migratedLegacyMessages());
        assertEquals("legacy value", config.getString("messages.player-only"));
    }

    @Test
    void anUnsupportedDefaultLanguageIsRejectedRatherThanIgnored() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("default-language", "de");

        assertThrows(
                IllegalArgumentException.class,
                () -> MessageService.finnishByDefault(config)
        );
        config.set("default-language", "FI");
        assertTrue(MessageService.finnishByDefault(config));
    }

    @Test
    void textWithNoAudienceFollowsTheConfiguredDefaultLanguage() {
        MessageService finnishServer = new MessageService(
                Map.of("greeting", "Hello"),
                Map.of("greeting", "Hei"),
                Logger.getAnonymousLogger(),
                true
        );

        // Loot and trade item lore, server list pings and console output have no
        // audience to read a preference from.
        assertEquals(Component.text("Hei"), finnishServer.component("greeting"));
        assertEquals(Component.text("Hello"), messages.component("greeting"));

        // A real player still overrides the server default in both directions.
        assertEquals(
                Component.text("Hello"),
                finnishServer.component(player(Locale.forLanguageTag("en-GB")), "greeting")
        );
    }

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
