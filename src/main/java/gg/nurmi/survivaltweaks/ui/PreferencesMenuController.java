package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.object.PlayerPreferences;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.PlayerExperienceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class PreferencesMenuController implements Listener {

    private static final int SOUNDS = 10;
    private static final int PARTICLES = 11;
    private static final int DIALOGS = 12;
    private static final int ACTION_BAR = 13;
    private static final int RECOVERY_COMPASS = 14;
    private static final int REDUCED_EFFECTS = 15;
    private static final int PLAYER_LIST = 16;
    private static final int LANGUAGE = 17;
    private static final int MENTIONS = 19;
    private static final int JOURNEY_GUIDANCE = 20;
    private static final int BACK = 22;
    private static final int PUBLIC_PROFILE = 24;
    private static final int MAIL = 25;

    private final Server server;
    private final PlayerExperienceService experience;
    private final MessageService messages;
    private final FeedbackService feedback;
    private Consumer<Player> back = Player::closeInventory;
    private Consumer<Player> changed = ignored -> {
    };

    public PreferencesMenuController(
            Server server,
            PlayerExperienceService experience,
            MessageService messages,
            FeedbackService feedback
    ) {
        this.server = server;
        this.experience = experience;
        this.messages = messages;
        this.feedback = feedback;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public void onChanged(Consumer<Player> action) {
        changed = action;
    }

    public void open(Player player) {
        PlayerPreferences preferences = experience.preferences(player);
        PreferencesHolder holder = new PreferencesHolder(
                player.getUniqueId(),
                server,
                messages.component(player, "ui.preferences.title")
        );
        Inventory inventory = holder.getInventory();
        inventory.setItem(SOUNDS, toggle(player, Material.NOTE_BLOCK, "sounds", preferences.soundsEnabled()));
        inventory.setItem(
                PARTICLES,
                toggle(player, Material.FIREWORK_STAR, "particles", preferences.particlesEnabled())
        );
        inventory.setItem(DIALOGS, toggle(player, Material.WRITABLE_BOOK, "dialogs", preferences.dialogsEnabled()));
        inventory.setItem(
                ACTION_BAR,
                toggle(player, Material.EXPERIENCE_BOTTLE, "action-bar", preferences.actionBarEnabled())
        );
        inventory.setItem(
                RECOVERY_COMPASS,
                toggle(
                        player,
                        Material.COMPASS,
                        "recovery-compass",
                        preferences.automaticRecoveryCompass()
                )
        );
        inventory.setItem(
                REDUCED_EFFECTS,
                toggle(
                        player,
                        Material.FEATHER,
                        "reduced-effects",
                        preferences.reducedEffects()
                )
        );
        inventory.setItem(
                PLAYER_LIST,
                toggle(
                        player,
                        Material.PLAYER_HEAD,
                        "player-list",
                        preferences.playerListEnabled()
                )
        );
        inventory.setItem(LANGUAGE, item(
                Material.KNOWLEDGE_BOOK,
                messages.component(player, "ui.preferences.language-label"),
                List.of(messages.component(
                        player,
                        "ui.preferences.language-value",
                        Placeholder.component(
                                "language",
                                messages.component(
                                        player,
                                        "ui.preferences.language." + preferences.language().name().toLowerCase()
                                )
                        )
                ))
        ));
        inventory.setItem(
                MENTIONS,
                toggle(
                        player,
                        Material.NAME_TAG,
                        "mention-notifications",
                        preferences.mentionNotificationsEnabled()
                )
        );
        inventory.setItem(
                JOURNEY_GUIDANCE,
                toggle(
                        player,
                        Material.MAP,
                        "journey-guidance",
                        preferences.journeyGuidanceEnabled()
                )
        );
        inventory.setItem(
                PUBLIC_PROFILE,
                toggle(
                        player,
                        Material.PLAYER_HEAD,
                        "public-profile",
                        preferences.publicProfileEnabled()
                )
        );
        inventory.setItem(
                MAIL,
                toggle(
                        player,
                        Material.WRITABLE_BOOK,
                        "mail",
                        preferences.mailEnabled()
                )
        );
        inventory.setItem(BACK, item(
                Material.ARROW,
                messages.component(player, "ui.common.back"),
                List.of()
        ));
        player.openInventory(inventory);
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PreferencesHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.ownerId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == BACK) {
            back.accept(player);
            return;
        }
        PlayerPreferences current = experience.preferences(player);
        if (slot == SOUNDS) {
            experience.update(player, value -> value.withSounds(!current.soundsEnabled()));
        } else if (slot == PARTICLES) {
            experience.update(player, value -> value.withParticles(!current.particlesEnabled()));
        } else if (slot == DIALOGS) {
            experience.update(player, value -> value.withDialogs(!current.dialogsEnabled()));
        } else if (slot == ACTION_BAR) {
            experience.update(player, value -> value.withActionBar(!current.actionBarEnabled()));
        } else if (slot == RECOVERY_COMPASS) {
            experience.update(
                    player,
                    value -> value.withAutomaticRecoveryCompass(!current.automaticRecoveryCompass())
            );
        } else if (slot == REDUCED_EFFECTS) {
            experience.update(player, value -> value.withReducedEffects(!current.reducedEffects()));
        } else if (slot == PLAYER_LIST) {
            experience.update(player, value -> value.withPlayerList(!current.playerListEnabled()));
        } else if (slot == MENTIONS) {
            experience.update(
                    player,
                    value -> value.withMentionNotifications(!current.mentionNotificationsEnabled())
            );
        } else if (slot == JOURNEY_GUIDANCE) {
            experience.update(
                    player,
                    value -> value.withJourneyGuidance(!current.journeyGuidanceEnabled())
            );
        } else if (slot == PUBLIC_PROFILE) {
            experience.update(
                    player,
                    value -> value.withPublicProfile(!current.publicProfileEnabled())
            );
        } else if (slot == MAIL) {
            experience.update(player, value -> value.withMail(!current.mailEnabled()));
        } else if (slot == LANGUAGE) {
            experience.update(player, value -> value.withLanguage(current.language().next()));
        } else {
            return;
        }
        changed.accept(player);
        feedback.play(player, FeedbackService.UI_CLICK);
        open(player);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PreferencesHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack toggle(Player player, Material material, String key, boolean enabled) {
        return item(
                material,
                messages.component(player, "ui.preferences." + key),
                List.of(messages.component(
                        player,
                        enabled ? "ui.preferences.enabled" : "ui.preferences.disabled"
                ))
        );
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class PreferencesHolder implements InventoryHolder {

        private final UUID ownerId;
        private final Inventory inventory;

        private PreferencesHolder(UUID ownerId, Server server, Component title) {
            this.ownerId = ownerId;
            inventory = server.createInventory(this, 27, title);
        }

        private UUID ownerId() {
            return ownerId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
