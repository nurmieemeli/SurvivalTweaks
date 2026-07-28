package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.service.AfkTracker;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerListService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class AfkCommand implements CommandExecutor {

    private final PlayerListService playerList;
    private final MessageService messages;

    public AfkCommand(PlayerListService playerList, MessageService messages) {
        this.playerList = playerList;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        AfkTracker.State state = playerList.toggleAfk(player);
        if (state == null) {
            messages.send(player, "afk.unavailable");
        } else {
            messages.send(player, state == AfkTracker.State.AFK ? "afk.enabled" : "afk.disabled");
        }
        return true;
    }
}
