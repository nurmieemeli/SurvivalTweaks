package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.listener.ChatListener;
import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatListenerTest {

    @Test
    void disablingFormattingAndMentionsLeavesTheActiveRendererAlone() {
        var event = mock(AsyncChatEvent.class);
        var configuration = mock(PluginSettings.class);

        new ChatListener(mock(MessageService.class), new SettingsService(configuration), mock(MentionService.class))
                .onChat(event);

        verify(event, never()).renderer(any(ChatRenderer.class));
    }

    @Test
    void mentionHighlightingUsesTheOtherPluginsRenderedMessageWhenFormattingIsDisabled() {
        var event = mock(AsyncChatEvent.class);
        var configuration = mock(PluginSettings.class);
        var mentions = mock(MentionService.class);
        var viewer = mock(Player.class);
        var sender = mock(Player.class);
        var name = Component.text("Alex");
        var message = Component.text("Hi @Sam");
        var format = Component.text("[Team] Alex: Hi @Sam");
        var highlighted = Component.text("[Team] Alex: Hi ").append(Component.text("@Sam"));
        when(configuration.mentionsEnabled()).thenReturn(true);
        when(event.renderer()).thenReturn((from, fromName, text, recipient) -> format);
        when(mentions.highlight(viewer, format)).thenReturn(highlighted);
        var installed = ArgumentCaptor.forClass(ChatRenderer.class);

        new ChatListener(mock(MessageService.class), new SettingsService(configuration), mentions).onChat(event);

        verify(event).renderer(installed.capture());
        assertEquals(highlighted, installed.getValue().render(sender, name, message, viewer));
    }

    @Test
    void playerNameOpensProfileFromFormattedChat() {
        MessageService messages = new MessageService(
                Map.of(
                        "chat-format", "<name>: <message>",
                        "chat-player-hover", "View <player>'s profile"
                ),
                Map.of(
                        "chat-format", "<name>: <message>",
                        "chat-player-hover", "Näytä pelaajan <player> profiili"
                ),
                Logger.getAnonymousLogger()
        );
        PluginSettings current = mock(PluginSettings.class);
        when(current.chatFormattingEnabled()).thenReturn(true);
        SettingsService settings = mock(SettingsService.class);
        when(settings.current()).thenReturn(current);
        MentionService mentions = mock(MentionService.class);
        when(mentions.highlight(any(), any(Component.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Player sender = mock(Player.class);
        when(sender.getName()).thenReturn("Alex");
        AsyncChatEvent event = mock(AsyncChatEvent.class);
        AtomicReference<ChatRenderer> renderer = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            renderer.set(invocation.getArgument(0));
            return null;
        }).when(event).renderer(any(ChatRenderer.class));

        new ChatListener(messages, settings, mentions).onChat(event);

        assertNotNull(renderer.get());
        Component rendered = renderer.get().render(
                sender,
                Component.text("Alex"),
                Component.text("Hello"),
                player(Locale.ENGLISH)
        );
        ClickEvent click = findClickEvent(rendered);
        assertNotNull(click);
        assertEquals(ClickEvent.runCommand("/profile Alex"), click);
    }

    @Test
    void onlyFinalUncancelledChatCanTriggerMentions() {
        var mentions = mock(MentionService.class);
        var listener = new ChatListener(mock(MessageService.class), mock(SettingsService.class), mentions);
        var event = mock(AsyncChatEvent.class);
        var player = mock(Player.class);
        Component message = Component.text("@Alex");
        when(event.getPlayer()).thenReturn(player);
        when(event.message()).thenReturn(message);
        when(event.isCancelled()).thenReturn(true);

        listener.onChatComplete(event);
        org.mockito.Mockito.verifyNoInteractions(mentions);

        when(event.isCancelled()).thenReturn(false);
        listener.onChatComplete(event);
        org.mockito.Mockito.verify(mentions).process(player, message, event.viewers());
    }

    private ClickEvent findClickEvent(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = findClickEvent(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Player player(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }

    @Test
    void chatFilteredAwayFromAPlayerDoesNotNotifyThatPlayer() {
        var sender = mock(Player.class);
        var target = mock(Player.class);
        when(sender.getName()).thenReturn("Sender");
        when(target.getName()).thenReturn("Target");
        when(sender.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(target.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(sender.isOnline()).thenReturn(true);
        when(sender.canSee(target)).thenReturn(true);
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        var server = mock(org.bukkit.Server.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        org.mockito.Mockito.doReturn(java.util.List.of(sender,target)).when(server).getOnlinePlayers();
        when(server.getPlayer(target.getUniqueId())).thenReturn(target);
        when(scheduler.runTask(org.mockito.ArgumentMatchers.eq(plugin),any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1,Runnable.class).run();
            return null;
        });
        var settings = mock(PluginSettings.class);
        when(settings.mentionsEnabled()).thenReturn(true);
        when(settings.mentionMaxPerMessage()).thenReturn(3);
        when(settings.mentionCooldown()).thenReturn(java.time.Duration.ZERO);
        var experience = mock(PlayerExperienceService.class);
        when(experience.preferences(any(Player.class))).thenReturn(gg.nurmi.survivaltweaks.object.PlayerPreferences.DEFAULTS);
        var playerList = mock(PlayerListService.class);
        when(playerList.isAfk(target.getUniqueId())).thenReturn(true);
        var messages = mock(MessageService.class);
        var feedback = mock(FeedbackService.class);
        var config = new SettingsService(settings);
        var mentions = new MentionService(plugin, config, messages, feedback, experience, playerList, java.time.Clock.systemUTC());
        var event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(sender);
        when(event.message()).thenReturn(Component.text("@Target"));
        when(event.viewers()).thenReturn(java.util.Set.of(sender));

        new ChatListener(messages, config, mentions).onChatComplete(event);

        org.mockito.Mockito.verifyNoInteractions(feedback, messages);

        when(event.viewers()).thenReturn(java.util.Set.of(sender, target));
        new ChatListener(messages, config, mentions).onChatComplete(event);
        org.mockito.Mockito.verify(feedback).play(target, FeedbackService.MENTION);
    }
}
