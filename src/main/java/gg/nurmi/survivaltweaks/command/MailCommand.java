package gg.nurmi.survivaltweaks.command;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.MailService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.ui.MailboxController;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MailCommand implements CommandExecutor, TabCompleter {

    private final Server server;
    private final MailService mail;
    private final MailboxController mailbox;
    private final MessageService messages;
    private final SettingsService settings;

    public MailCommand(
            Server server,
            MailService mail,
            MailboxController mailbox,
            MessageService messages
    ) {
        this(server, mail, mailbox, messages, null);
    }

    public MailCommand(
            Server server,
            MailService mail,
            MailboxController mailbox,
            MessageService messages,
            SettingsService settings
    ) {
        this.server = server;
        this.mail = mail;
        this.mailbox = mailbox;
        this.messages = messages;
        this.settings = settings;
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
        if (arguments.length == 0
                || (arguments.length == 1 && arguments[0].equalsIgnoreCase("inbox"))) {
            mailbox.open(player);
            return true;
        }
        if (arguments.length >= 3 && arguments[0].equalsIgnoreCase("send")) {
            OfflinePlayer recipient = mail.findRecipient(arguments[1]);
            mail.sendWithFeedbackAsync(
                    player,
                    recipient,
                    String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length))
            );
            return true;
        }
        if (arguments.length == 2
                && (arguments[0].equalsIgnoreCase("block")
                || arguments[0].equalsIgnoreCase("unblock"))) {
            OfflinePlayer target = mail.findRecipient(arguments[1]);
            if (target == null) {
                messages.send(player, "mail.result.unknown-player");
                return true;
            }
            boolean block = arguments[0].equalsIgnoreCase("block");
            boolean changed = block
                    ? mail.block(player, target)
                    : mail.unblock(player, target);
            messages.send(
                    player,
                    changed
                            ? (block ? "mail.blocked" : "mail.unblocked")
                            : (block ? "mail.already-blocked" : "mail.not-blocked"),
                    Placeholder.component(
                            "player",
                            messages.formatPlayerName(target.getName() == null ? arguments[1] : target.getName(), settings)
                    )
            );
            return true;
        }
        messages.send(player, "mail.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments
    ) {
        if (arguments.length == 1) {
            String prefix = arguments[0].toLowerCase(Locale.ROOT);
            return List.of("inbox", "send", "block", "unblock").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (arguments.length == 2
                && List.of("send", "block", "unblock").contains(
                arguments[0].toLowerCase(Locale.ROOT))) {
            String prefix = arguments[1].toLowerCase(Locale.ROOT);
            return server.getOnlinePlayers().stream()
                    .filter(player -> !(sender instanceof Player source)
                            || !player.getUniqueId().equals(source.getUniqueId()))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }
}
