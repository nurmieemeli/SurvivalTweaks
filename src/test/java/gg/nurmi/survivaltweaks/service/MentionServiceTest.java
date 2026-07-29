package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.Profile;
import gg.nurmi.survivaltweaks.storage.ProfileStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MentionServiceTest {

    @TempDir
    Path directory;

    private final Logger logger = Logger.getAnonymousLogger();
    private ProfileRepository profiles;

    @BeforeEach
    void openProfiles() {
        profiles = new ProfileRepository(new ProfileStore(directory, logger), logger);
    }

    @AfterEach
    void closeProfiles() {
        profiles.close();
    }

    @Test
    void onlyWholeNamesPrecededByAnAtSignCount() {
        assertEquals(List.of("Alex"), MentionService.mentionedNames("hi @Alex", 3));
        assertEquals(List.of("Alex"), MentionService.mentionedNames("@Alex, look", 3));
        assertEquals(List.of("Alex"), MentionService.mentionedNames("(@Alex)", 3));
    }

    @Test
    void emailsAndMidWordAtSignsAreNotMentions() {
        assertEquals(List.of(), MentionService.mentionedNames("mail me at sam@Alex.com", 3));
        assertEquals(List.of(), MentionService.mentionedNames("x@Alex", 3));
        assertEquals(List.of(), MentionService.mentionedNames("no mention here", 3));
        // Underscores are legal in Minecraft names, so they belong to the mention.
        assertEquals(List.of("Alex_"), MentionService.mentionedNames("@Alex_", 3));
    }

    @Test
    void namesLongerThanMinecraftAllowsAreIgnored() {
        assertEquals(List.of(), MentionService.mentionedNames("@" + "a".repeat(17), 3));
        assertEquals(List.of("a".repeat(16)), MentionService.mentionedNames("@" + "a".repeat(16), 3));
    }

    @Test
    void duplicatesCollapseCaseInsensitivelyKeepingTheFirstSpelling() {
        assertEquals(
                List.of("Alex"),
                MentionService.mentionedNames("@Alex @alex @ALEX", 5)
        );
    }

    @Test
    void theMaximumCapsHowManyDistinctNamesAreReturned() {
        assertEquals(
                List.of("One", "Two"),
                MentionService.mentionedNames("@One @Two @Three", 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MentionService.mentionedNames("@One", 0)
        );
    }

    @Test
    void yourOwnNameIsHighlightedRegardlessOfCase() {
        Player viewer = player("Alex");
        MentionService mentions = mentions(defaults());

        Component highlighted = mentions.highlight(viewer, Component.text("hey @alex and @Sam"));

        // The mention is rewritten to the player's canonical spelling and styled.
        assertEquals("hey @Alex and @Sam", plain(highlighted));
        assertTrue(hasBoldYellow(highlighted));
    }

    @Test
    void otherPlayersNamesAreLeftAlone() {
        Player viewer = player("Alex");
        MentionService mentions = mentions(defaults());

        assertEquals(
                "hey @Sam",
                plain(mentions.highlight(viewer, Component.text("hey @Sam")))
        );
    }

    @Test
    void disablingMentionNotificationsAlsoTurnsOffHighlighting() {
        Player viewer = player("Alex");
        Profile profile = profiles.load(viewer.getUniqueId());
        profile.preferences(profile.preferences().withMentionNotifications(false));
        profiles.save(profile);

        MentionService mentions = mentions(defaults());
        mentions.preferenceChanged(viewer);
        Component message = Component.text("hey @Alex");

        assertSame(message, mentions.highlight(viewer, message));
    }

    @Test
    void disablingMentionsOnTheServerLeavesMessagesUntouched() {
        Player viewer = player("Alex");
        MentionService mentions = mentions(settings(config -> config.set("mentions.enabled", false)));
        Component message = Component.text("hey @Alex");

        assertSame(message, mentions.highlight(viewer, message));
    }

    @Test
    void nonPlayerAudiencesAreNeverHighlighted() {
        MentionService mentions = mentions(defaults());
        Component message = Component.text("hey @Alex");

        assertSame(message, mentions.highlight(mock(net.kyori.adventure.audience.Audience.class), message));
    }

    private boolean hasBoldYellow(Component component) {
        if (component.color() == NamedTextColor.YELLOW
                && component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            return true;
        }
        return component.children().stream().anyMatch(this::hasBoldYellow);
    }

    private MentionService mentions(SettingsService settings) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        return new MentionService(
                plugin,
                settings,
                new MessageService(Map.of(), Map.of(), logger),
                new FeedbackService(new YamlConfiguration(), logger),
                new PlayerExperienceService(profiles),
                mock(PlayerListService.class),
                Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private SettingsService defaults() {
        return settings(config -> {
        });
    }

    private SettingsService settings(Consumer<YamlConfiguration> overrides) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8
        ));
        overrides.accept(config);
        return new SettingsService(PluginSettings.validate(config));
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        when(player.getName()).thenReturn(name);
        when(player.locale()).thenReturn(Locale.US);
        return player;
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
