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

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatListenerTest {

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
}
