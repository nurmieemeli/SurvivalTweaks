package gg.nurmi.survivaltweaks;

import gg.nurmi.survivaltweaks.listener.ChatListener;
import gg.nurmi.survivaltweaks.listener.ConnectionListener;
import gg.nurmi.survivaltweaks.listener.ContainerLockListener;
import gg.nurmi.survivaltweaks.listener.TeleportSafetyListener;
import gg.nurmi.survivaltweaks.service.AtmosphereService;
import gg.nurmi.survivaltweaks.service.CustomDeathMessageService;
import gg.nurmi.survivaltweaks.service.CustomEnchantAcquisitionService;
import gg.nurmi.survivaltweaks.service.CustomEnchantEffectService;
import gg.nurmi.survivaltweaks.service.DeathRecoveryService;
import gg.nurmi.survivaltweaks.service.MaintenanceService;
import gg.nurmi.survivaltweaks.service.NewPlayerSpawnService;
import gg.nurmi.survivaltweaks.service.PlayerListService;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListenerContractTest {

    private static final List<Class<?>> CORE_LISTENERS = List.of(
            ChatListener.class,
            ConnectionListener.class,
            ContainerLockListener.class,
            TeleportSafetyListener.class,
            PlayerListService.class,
            MaintenanceService.class,
            NewPlayerSpawnService.class,
            DeathRecoveryService.class,
            CustomDeathMessageService.class,
            CustomEnchantAcquisitionService.class,
            CustomEnchantEffectService.class,
            AtmosphereService.class
    );

    @Test
    void everyCoreEventHandlerHasAPaperCompatibleSignature() {
        for (Class<?> listener : CORE_LISTENERS) {
            for (Method method : listener.getDeclaredMethods()) {
                if (method.getAnnotation(EventHandler.class) == null) {
                    continue;
                }
                assertTrue(Modifier.isPublic(method.getModifiers()), method.toString());
                assertEquals(void.class, method.getReturnType(), method.toString());
                assertEquals(1, method.getParameterCount(), method.toString());
                assertTrue(
                        Event.class.isAssignableFrom(method.getParameterTypes()[0]),
                        method.toString()
                );
            }
        }
    }

    @Test
    void protectionAndObservationHandlersKeepTheirRequiredPriorities() throws Exception {
        EventHandler preLogin = handler(
                ConnectionListener.class,
                "onPreLogin",
                AsyncPlayerPreLoginEvent.class
        );
        assertEquals(EventPriority.HIGHEST, preLogin.priority());

        EventHandler lockOpen = handler(
                ContainerLockListener.class,
                "onOpen",
                InventoryOpenEvent.class
        );
        assertEquals(EventPriority.HIGHEST, lockOpen.priority());
        assertTrue(lockOpen.ignoreCancelled());

        EventHandler playerActivity = handler(
                PlayerListService.class,
                "onMove",
                PlayerMoveEvent.class
        );
        assertEquals(EventPriority.MONITOR, playerActivity.priority());
        assertTrue(playerActivity.ignoreCancelled());

        EventHandler teleportActivity = handler(
                TeleportSafetyListener.class,
                "onMove",
                PlayerMoveEvent.class
        );
        assertEquals(EventPriority.MONITOR, teleportActivity.priority());
        assertTrue(teleportActivity.ignoreCancelled());
    }

    private EventHandler handler(
            Class<?> owner,
            String name,
            Class<? extends Event> event
    ) throws Exception {
        return owner.getDeclaredMethod(name, event).getAnnotation(EventHandler.class);
    }
}
