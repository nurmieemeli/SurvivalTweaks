package gg.nurmi.survivaltweaks.listener;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.MentionService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class ChatListener implements Listener {

    private final MessageService messages;
    private final SettingsService settings;
    private final MentionService mentions;

    public ChatListener(
            MessageService messages,
            SettingsService settings,
            MentionService mentions
    ) {
        this.messages = messages;
        this.settings = settings;
        this.mentions = mentions;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        mentions.process(event.getPlayer(), event.message());
        if (settings.current().chatFormattingEnabled()) {
            event.renderer((sender, senderName, message, audience) -> mentions.highlight(
                    audience,
                    messages.component(
                            audience,
                            "chat-format",
                            Placeholder.component(
                                    "name",
                                    interactiveName(sender.getName(), senderName, audience)
                            ),
                            Placeholder.component("message", message)
                    )
            ));
        } else {
            event.renderer((sender, senderName, message, audience) -> {
                Component rendered = Component.translatable(
                        "chat.type.text",
                        interactiveName(sender.getName(), senderName, audience),
                        message
                );
                return settings.current().mentionsEnabled()
                        ? mentions.highlight(audience, rendered)
                        : rendered;
            });
        }
    }

    private Component interactiveName(
            String playerName,
            Component renderedName,
            net.kyori.adventure.audience.Audience audience
    ) {
        return renderedName
                .clickEvent(ClickEvent.runCommand("/profile " + playerName))
                .hoverEvent(HoverEvent.showText(messages.component(
                        audience,
                        "chat-player-hover",
                        Placeholder.unparsed("player", playerName)
                )));
    }
}
