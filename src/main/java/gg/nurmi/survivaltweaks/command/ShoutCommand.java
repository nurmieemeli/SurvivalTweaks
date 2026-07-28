package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ShoutCommand implements CommandExecutor {

    private final Server server;
    private final MessageService messages;
    private final FeedbackService feedback;

    public ShoutCommand(Server server, MessageService messages, FeedbackService feedback) {
        this.server = server;
        this.messages = messages;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (sender instanceof Player) {
            messages.send(sender, "shout.console-only");
            return true;
        }
        if (arguments.length == 0) {
            messages.send(sender, "shout.usage");
            return true;
        }

        server.getOnlinePlayers().forEach(player -> {
            player.sendMessage(messages.component(
                    player,
                    "shout.message",
                    Placeholder.unparsed("message", String.join(" ", arguments))
            ));
            feedback.play(player, FeedbackService.ANNOUNCEMENT);
        });
        return true;
    }
}
