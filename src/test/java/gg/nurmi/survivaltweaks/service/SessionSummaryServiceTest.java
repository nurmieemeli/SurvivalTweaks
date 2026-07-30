package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.TeleportRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionSummaryServiceTest {

    private final UUID playerId = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final TeleportRequestService requests = mock(TeleportRequestService.class);
    private final DeathRecoveryService deathRecovery = mock(DeathRecoveryService.class);
    private final MaintenanceService maintenance = mock(MaintenanceService.class);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-30T12:00:00Z"),
            ZoneOffset.UTC
    );
    private final MessageService messages = new MessageService(
            Map.ofEntries(
                    Map.entry("session-summary.unread", "<count> unread"),
                    Map.entry("session-summary.unread-one", "<count> unread"),
                    Map.entry("session-summary.teleports", "<count> requests"),
                    Map.entry("session-summary.teleports-one", "<count> request"),
                    Map.entry("session-summary.death-marker", "death marker"),
                    Map.entry("session-summary.maintenance", "maintenance"),
                    Map.entry("session-summary.restart", "restart in <seconds>s"),
                    Map.entry("session-summary.open", "[Open]"),
                    Map.entry("session-summary.open-hover", "Open hub"),
                    Map.entry("session-summary.line", "Session: <items> <open>")
            ),
            Map.of(),
            Logger.getAnonymousLogger()
    );

    @Test
    void summaryCombinesOnlyRelevantSessionState() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.locale()).thenReturn(Locale.ENGLISH);
        when(notifications.unread(playerId)).thenReturn(2L);
        when(requests.incomingRequests(playerId, clock.instant()))
                .thenReturn(List.of(mock(TeleportRequest.class)));
        when(deathRecovery.hasActiveMarker(playerId)).thenReturn(true);
        when(maintenance.status()).thenReturn(
                new MaintenanceService.Status(true, true, false, 90, false)
        );

        Component summary = service().summary(player);

        assertNotNull(summary);
        String plain = PlainTextComponentSerializer.plainText().serialize(summary);
        assertTrue(plain.contains("2 unread"));
        assertTrue(plain.contains("1 request"));
        assertTrue(plain.contains("death marker"));
        assertTrue(plain.contains("maintenance"));
        assertTrue(plain.contains("restart in 90s"));
        assertTrue(hasClickEvent(summary));
    }

    @Test
    void emptySessionDoesNotProduceNoise() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(requests.incomingRequests(playerId, clock.instant())).thenReturn(List.of());
        when(maintenance.status()).thenReturn(
                new MaintenanceService.Status(false, false, false, 0, false)
        );

        assertNull(service().summary(player));
    }

    private SessionSummaryService service() {
        return new SessionSummaryService(
                mock(JavaPlugin.class),
                messages,
                notifications,
                requests,
                deathRecovery,
                maintenance,
                clock
        );
    }

    private boolean hasClickEvent(Component component) {
        return component.clickEvent() != null
                || component.children().stream().anyMatch(this::hasClickEvent);
    }
}
