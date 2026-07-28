package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.object.Home;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.ProfileRepository;
import gg.nurmi.survivaltweaks.service.SafeTeleportService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Material;

public final class HomeMenuController implements Listener {

    private final Server server;
    private final ProfileRepository profiles;
    private final MessageService messages;
    private final SafeTeleportService teleports;
    private final FeedbackService feedback;
    private final SettingsService settings;
    private final TextPromptService prompts;
    private final ConfirmationDialogService dialogs;
    private final Map<UUID, String> reorderSelections = new HashMap<>();
    private Consumer<Player> back = Player::closeInventory;

    private static final List<Material> HOME_ICONS = List.of(
            Material.ENDER_PEARL,
            Material.RED_BED,
            Material.COMPASS,
            Material.OAK_DOOR,
            Material.GRASS_BLOCK,
            Material.CHEST,
            Material.CAMPFIRE,
            Material.NETHER_STAR
    );

    public HomeMenuController(
            Server server,
            ProfileRepository profiles,
            MessageService messages,
            SafeTeleportService teleports,
            FeedbackService feedback,
            SettingsService settings,
            TextPromptService prompts,
            ConfirmationDialogService dialogs
    ) {
        this.server = server;
        this.profiles = profiles;
        this.messages = messages;
        this.teleports = teleports;
        this.feedback = feedback;
        this.settings = settings;
        this.prompts = prompts;
        this.dialogs = dialogs;
    }

    public void backTo(Consumer<Player> action) {
        back = action;
    }

    public boolean open(Player player, List<Home> homes) {
        if (!settings.current().homeMenuEnabled()) {
            return false;
        }
        openPage(player, homes, 0);
        return true;
    }

    public void teleport(Player player, Home home) {
        Optional<Location> destination = home.resolve(server);
        if (destination.isEmpty()) {
            messages.send(
                    player,
                    "home.world-missing",
                    Placeholder.unparsed("home", home.name()),
                    Placeholder.unparsed("world", home.worldName())
            );
            feedback.play(player, FeedbackService.TELEPORT_CANCELLED);
            return;
        }

        Location resolved = destination.orElseThrow();
        teleports.begin(
                player,
                resolved::clone,
                messages.component(
                        player,
                        "home.teleported",
                        Placeholder.unparsed("home", home.name())
                ),
                messages.component(
                        player,
                        "teleport.destination.home",
                        Placeholder.unparsed("home", home.name())
                ),
                home.arrivalStyle().cue()
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof HomeCustomizationMenu editor) {
            editorClick(event, editor);
            return;
        }
        if (!(event.getView().getTopInventory().getHolder(false) instanceof HomeMenu menu)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(menu.ownerId())) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == HomeMenu.CLOSE_SLOT) {
            feedback.play(player, FeedbackService.UI_CLICK);
            back.accept(player);
            return;
        }
        if (slot == HomeMenu.PREVIOUS_SLOT && menu.hasPreviousPage()) {
            feedback.play(player, FeedbackService.UI_CLICK);
            openPage(player, profiles.load(player.getUniqueId()).homes(), menu.page() - 1);
            return;
        }
        if (slot == HomeMenu.NEXT_SLOT && menu.hasNextPage()) {
            feedback.play(player, FeedbackService.UI_CLICK);
            openPage(player, profiles.load(player.getUniqueId()).homes(), menu.page() + 1);
            return;
        }

        Optional<Home> selected = menu.homeAt(slot)
                .flatMap(home -> profiles.load(player.getUniqueId()).home(home.name()));
        if (selected.isPresent()) {
            feedback.play(player, FeedbackService.UI_CLICK);
            if (event.isShiftClick()) {
                reorder(player, menu, selected.orElseThrow());
            } else if (event.isRightClick()) {
                openEditor(player, selected.orElseThrow());
            } else {
                player.closeInventory();
                teleport(player, selected.orElseThrow());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof HomeMenu
                || event.getView().getTopInventory().getHolder(false) instanceof HomeCustomizationMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof HomeMenu
                || event.getInventory().getHolder(false) instanceof HomeCustomizationMenu) {
            reorderSelections.remove(event.getPlayer().getUniqueId());
        }
    }

    private void editorClick(InventoryClickEvent event, HomeCustomizationMenu editor) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(editor.ownerId())) {
            return;
        }
        var profile = profiles.load(player.getUniqueId());
        Optional<Home> selected = profile.home(editor.homeName());
        if (selected.isEmpty()) {
            player.closeInventory();
            return;
        }
        Home home = selected.orElseThrow();
        int slot = event.getRawSlot();
        if (slot == HomeCustomizationMenu.BACK_SLOT) {
            open(player, profile.homes());
            return;
        }
        if (slot == HomeCustomizationMenu.ICON_SLOT) {
            int current = HOME_ICONS.indexOf(home.icon());
            Material next = HOME_ICONS.get((current + 1 + HOME_ICONS.size()) % HOME_ICONS.size());
            update(profile, home.withIcon(next));
            openEditor(player, home.withIcon(next));
            return;
        }
        if (slot == HomeCustomizationMenu.FAVORITE_SLOT) {
            Home updated = home.withFavorite(!home.favorite());
            update(profile, updated);
            openEditor(player, updated);
            return;
        }
        if (slot == HomeCustomizationMenu.CATEGORY_SLOT) {
            Home updated = home.withCategory(home.category().next());
            update(profile, updated);
            openEditor(player, updated);
            return;
        }
        if (slot == HomeCustomizationMenu.ARRIVAL_STYLE_SLOT) {
            Home updated = home.withArrivalStyle(home.arrivalStyle().next());
            update(profile, updated);
            feedback.play(player, updated.arrivalStyle().cue());
            openEditor(player, updated);
            return;
        }
        if (slot == HomeCustomizationMenu.UPDATE_LOCATION_SLOT) {
            Home updated = home.withLocation(player.getLocation());
            update(profile, updated);
            messages.send(player, "ui.home-editor.location-updated");
            feedback.play(player, FeedbackService.HOME_SAVED);
            openEditor(player, updated);
            return;
        }
        if (slot == HomeCustomizationMenu.RENAME_SLOT) {
            prompts.request(player, "ui.home-editor.rename-prompt", (responding, text) -> {
                String updatedName = text.strip();
                if (updatedName.isBlank() || updatedName.length() > 16) {
                    messages.send(responding, "ui.home-editor.invalid-name");
                    return;
                }
                var currentProfile = profiles.load(responding.getUniqueId());
                if (!currentProfile.renameHome(home.name(), updatedName)) {
                    messages.send(responding, "ui.home-editor.name-taken");
                    return;
                }
                profiles.save(currentProfile);
                messages.send(
                        responding,
                        "ui.home-editor.renamed",
                        Placeholder.unparsed("home", updatedName)
                );
                openEditor(responding, currentProfile.home(updatedName).orElseThrow());
            });
            return;
        }
        if (slot == HomeCustomizationMenu.DESCRIPTION_SLOT) {
            prompts.request(player, "ui.home-editor.description-prompt", (responding, text) -> {
                if (text.length() > 80) {
                    messages.send(responding, "ui.text-prompt.too-long");
                    return;
                }
                var currentProfile = profiles.load(responding.getUniqueId());
                currentProfile.home(home.name()).ifPresent(current -> {
                    Home updated = current.withDescription(text.equals("-") ? "" : text);
                    update(currentProfile, updated);
                    openEditor(responding, updated);
                });
            });
            return;
        }
        if (slot == HomeCustomizationMenu.EARLIER_SLOT || slot == HomeCustomizationMenu.LATER_SLOT) {
            move(profile, home, slot == HomeCustomizationMenu.EARLIER_SLOT ? -1 : 1);
            openEditor(player, profile.home(home.name()).orElseThrow());
            return;
        }
        if (slot == HomeCustomizationMenu.DELETE_SLOT) {
            Runnable delete = () -> {
                var current = profiles.load(player.getUniqueId());
                if (current.removeHome(home.name())) {
                    profiles.save(current);
                    messages.send(
                            player,
                            "delete-home.deleted",
                            Placeholder.unparsed("home", home.name())
                    );
                    feedback.play(player, FeedbackService.HOME_DELETED);
                }
                open(player, current.homes());
            };
            player.closeInventory();
            if (!dialogs.showHomeDelete(player, home.name(), ignored -> delete.run())) {
                prompts.request(
                        player,
                        "ui.home-editor.delete-prompt",
                        (responding, text) -> {
                            if (text.equalsIgnoreCase(home.name())) {
                                delete.run();
                            } else {
                                messages.send(responding, "ui.home-editor.delete-cancelled");
                            }
                        }
                );
            }
        }
    }

    private void openEditor(Player player, Home home) {
        player.openInventory(new HomeCustomizationMenu(server, messages, player, home).getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }

    private void update(gg.nurmi.survivaltweaks.object.Profile profile, Home home) {
        profile.updateHome(home);
        profiles.save(profile);
    }

    private void move(gg.nurmi.survivaltweaks.object.Profile profile, Home selected, int direction) {
        java.util.ArrayList<Home> ordered = profile.homes().stream()
                .filter(home -> home.favorite() == selected.favorite()
                        && home.category() == selected.category())
                .sorted(HomeMenu::compareHomes)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int index = java.util.stream.IntStream.range(0, ordered.size())
                .filter(position -> ordered.get(position).name().equalsIgnoreCase(selected.name()))
                .findFirst()
                .orElse(-1);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= ordered.size()) {
            return;
        }
        java.util.Collections.swap(ordered, index, target);
        for (int position = 0; position < ordered.size(); position++) {
            profile.updateHome(ordered.get(position).withOrder(position));
        }
        profiles.save(profile);
    }

    private void reorder(Player player, HomeMenu menu, Home target) {
        String selectedName = reorderSelections.remove(player.getUniqueId());
        if (selectedName == null) {
            reorderSelections.put(player.getUniqueId(), target.name());
            messages.send(
                    player,
                    "ui.home-menu.reorder-selected",
                    Placeholder.unparsed("home", target.name())
            );
            return;
        }
        var profile = profiles.load(player.getUniqueId());
        Optional<Home> selected = profile.home(selectedName);
        if (selected.isEmpty() || selectedName.equalsIgnoreCase(target.name())) {
            messages.send(player, "ui.home-menu.reorder-cancelled");
            return;
        }
        java.util.ArrayList<Home> ordered = new java.util.ArrayList<>(profile.homes());
        ordered.sort(HomeMenu::compareHomes);
        Home original = selected.orElseThrow();
        Home moving = original.favorite() != target.favorite() || original.category() != target.category()
                ? original.withFavorite(target.favorite()).withCategory(target.category())
                : original;
        if (moving != original) {
            profile.updateHome(moving);
        }
        ordered.removeIf(home -> home.name().equalsIgnoreCase(moving.name()));
        int destination = java.util.stream.IntStream.range(0, ordered.size())
                .filter(index -> ordered.get(index).name().equalsIgnoreCase(target.name()))
                .findFirst()
                .orElse(ordered.size());
        ordered.add(destination, moving);
        for (int index = 0; index < ordered.size(); index++) {
            profile.updateHome(ordered.get(index).withOrder(index));
        }
        profiles.save(profile);
        messages.send(player, "ui.home-menu.reordered");
        openPage(player, profile.homes(), menu.page());
    }

    private void openPage(Player player, List<Home> homes, int page) {
        HomeMenu menu = new HomeMenu(server, messages, player, homes, page);
        player.openInventory(menu.getInventory());
        feedback.play(player, FeedbackService.UI_OPEN);
    }
}
