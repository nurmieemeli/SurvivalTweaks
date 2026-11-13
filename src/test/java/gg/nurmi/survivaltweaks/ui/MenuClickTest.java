package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.command.teleport.TeleportAcceptCommand;
import gg.nurmi.survivaltweaks.object.TeleportRequest;
import gg.nurmi.survivaltweaks.service.MessageService;
import gg.nurmi.survivaltweaks.service.TeleportRequestService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MenuClickTest {

    @Test
    void defersTransitionsAndSkipsClicksForAClosedView() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Runnable action = mock(Runnable.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getSize()).thenReturn(27);
        SurvivalTweaksMenu.afterClick(plugin, event, player, action);

        var scheduled = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(org.mockito.ArgumentMatchers.eq(plugin), scheduled.capture());
        verifyNoInteractions(action);
        when(view.getTopInventory()).thenReturn(mock(Inventory.class));
        scheduled.getValue().run();
        verifyNoInteractions(action);
        when(view.getTopInventory()).thenReturn(top);
        scheduled.getValue().run();
        verify(action).run();
    }

    @Test
    void teleportInboxAndItsActionsRequireTheCommandPermission() {
        Player player = mock(Player.class);
        UUID recipient = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(recipient);
        TeleportRequestService requests = mock(TeleportRequestService.class);
        MessageService messages = mock(MessageService.class);
        Clock clock = Clock.systemUTC();
        TeleportAcceptCommand actions = new TeleportAcceptCommand(null, requests, messages, null, null, clock, null);
        TeleportInboxController inbox = new TeleportInboxController(null, null, requests, actions, messages, null, clock);
        TeleportRequest request = new TeleportRequest(UUID.randomUUID(), recipient, Instant.MAX);

        assertFalse(inbox.open(player));
        actions.acceptRequest(player, request);
        actions.declineRequest(player, request);
        verify(messages, org.mockito.Mockito.times(3))
                .requirePermission(player, "survivaltweaks.command.teleportaccept");
        verifyNoInteractions(requests);
    }
}
