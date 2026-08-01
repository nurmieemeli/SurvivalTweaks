package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.NotificationType;
import gg.nurmi.survivaltweaks.object.PlayerNotification;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MailService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class MailboxController implements Listener {

    private static final int CLEAR_READ_SLOT = 48;
    private static final int BACK_SLOT = 49;

    private final Server server;
    private final ProfileRepository profiles;
    private final MailService mail;
    private final TextPromptService prompts;
    private final MessageService messages;
    private final FeedbackService feedback;
    private final Clock clock;
    private Consumer<Player> back = Player::closeInventory;

    public MailboxController(
            Server server,
            ProfileRepository profiles,
            MailService mail,
            TextPromptService prompts,
            MessageService messages,
            FeedbackService feedback,
            Clock clock
    ) {
        this.server = server;
        this.profiles = profiles;
        this.mail = mail;
        this.prompts = prompts;
        this.messages = messages;
        this.feedback = feedback;
        this.clock = clock;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void compose(Player sender, OfflinePlayer recipient) {
        if (recipient == null) {
            messages.send(sender, "mail.result.unknown-player");
            return;
        }
        prompts.request(
                sender,
                "mail.prompt",
                (responding, text) -> mail.sendWithFeedback(responding, recipient, text)
        );
    }

    public void open(Player player) {
        List<PlayerNotification> letters = profiles.load(player.getUniqueId()).notifications().stream()
                .filter(notification -> notification.type() == NotificationType.MAIL)
                .limit(45)
                .toList();
        long unread = letters.stream().filter(letter -> !letter.read()).count();
        MailboxHolder holder = new MailboxHolder(
                player.getUniqueId(),
                letters,
                server,
                messages.component(
                        player,
                        MessageService.plural("ui.mailbox.title", unread),
                        Placeholder.unparsed("unread", Long.toString(unread))
                )
        );
        for (int index = 0; index < letters.size(); index++) {
            PlayerNotification letter = letters.get(index);
            long minutes = Math.max(
                    0,
                    Duration.between(letter.createdAt(), clock.instant()).toMinutes()
            );
            holder.getInventory().setItem(index, item(
                    letter.read() ? Material.PAPER : Material.WRITABLE_BOOK,
                    messages.component(
                            player,
                            "ui.mailbox.letter",
                            Placeholder.unparsed("sender", letter.actor())
                    ),
                    List.of(
                            messages.component(
                                    player,
                                    "ui.mailbox.message",
                                    Placeholder.unparsed("message", letter.detail())
                            ),
                            messages.component(
                                    player,
                                    MessageService.plural("ui.notifications.age", minutes),
                                    Placeholder.unparsed("minutes", Long.toString(minutes))
                            ),
                            messages.component(player, "ui.mailbox.actions")
                    )
            ));
        }
        holder.getInventory().setItem(CLEAR_READ_SLOT, item(
                Material.LAVA_BUCKET,
                messages.component(player, "ui.mailbox.clear-read"),
                List.of()
        ));
        holder.getInventory().setItem(BACK_SLOT, item(
                Material.ARROW,
                messages.component(player, "ui.common.back"),
                List.of()
        ));
        player.openInventory(holder.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MailboxHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        int slot = event.getRawSlot();
        var profile = profiles.load(player.getUniqueId());
        if (slot == BACK_SLOT) {
            back.accept(player);
            return;
        }
        if (slot == CLEAR_READ_SLOT) {
            profile.clearReadMail();
            profiles.save(profile);
            feedback.play(player, FeedbackService.UI_CLICK);
            open(player);
            return;
        }
        if (slot < 0 || slot >= holder.letters().size()) {
            return;
        }
        PlayerNotification letter = holder.letters().get(slot);
        if (event.isShiftClick() && event.isRightClick() && letter.actorId() != null) {
            OfflinePlayer sender = server.getOfflinePlayer(letter.actorId());
            mail.block(player, sender);
            profile.markNotificationRead(letter.id());
            profiles.save(profile);
            messages.send(
                    player,
                    "mail.blocked",
                    Placeholder.unparsed("player", letter.actor())
            );
        } else if (event.isShiftClick()) {
            profile.removeNotification(letter.id());
            profiles.save(profile);
        } else if (event.isRightClick() && letter.actorId() != null) {
            profile.markNotificationRead(letter.id());
            profiles.save(profile);
            compose(player, server.getOfflinePlayer(letter.actorId()));
            return;
        } else {
            profile.markNotificationRead(letter.id());
            profiles.save(profile);
        }
        feedback.play(player, FeedbackService.UI_CLICK);
        open(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MailboxHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class MailboxHolder implements SurvivalTweaksMenu {

        private final UUID ownerId;
        private final List<PlayerNotification> letters;
        private final Inventory inventory;

        private MailboxHolder(
                UUID ownerId,
                List<PlayerNotification> letters,
                Server server,
                Component title
        ) {
            this.ownerId = ownerId;
            this.letters = List.copyOf(letters);
            this.inventory = server.createInventory(this, 54, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        private List<PlayerNotification> letters() {
            return letters;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
