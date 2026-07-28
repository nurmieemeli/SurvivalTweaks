package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.TeleportRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");

    @Test
    void aSenderCanOnlyHaveOneActiveRequest() {
        TeleportRequestService service = new TeleportRequestService();
        UUID sender = UUID.randomUUID();

        assertTrue(service.create(sender, UUID.randomUUID(), NOW, Duration.ofSeconds(30)).isPresent());
        assertTrue(service.create(sender, UUID.randomUUID(), NOW, Duration.ofSeconds(30)).isEmpty());
        assertTrue(service.hasOutgoingRequest(sender, NOW.plusSeconds(29)));
    }

    @Test
    void expiredRequestsArePurgedAtTheirDeadline() {
        TeleportRequestService service = new TeleportRequestService();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        service.create(sender, recipient, NOW, Duration.ofSeconds(30));

        assertEquals(1, service.incomingRequests(recipient, NOW.plusSeconds(29)).size());
        assertEquals(
                java.util.List.of(sender),
                service.removeExpired(NOW.plusSeconds(30)).stream()
                        .map(TeleportRequest::senderId)
                        .toList()
        );
        assertFalse(service.hasOutgoingRequest(sender, NOW.plusSeconds(30)));
    }

    @Test
    void consumingAndDisconnectingRemoveRequests() {
        TeleportRequestService service = new TeleportRequestService();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        TeleportRequest request = service.create(
                sender,
                recipient,
                NOW,
                Duration.ofSeconds(30)
        ).orElseThrow();

        assertTrue(service.consume(request));
        assertEquals(0, service.size());

        service.create(sender, recipient, NOW, Duration.ofSeconds(30));
        service.removeForPlayer(recipient);
        assertEquals(0, service.size());
    }

    @Test
    void inboxOrdersMultipleSendersByExpiration() {
        TeleportRequestService service = new TeleportRequestService();
        UUID recipient = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID sooner = UUID.randomUUID();

        service.create(later, recipient, NOW, Duration.ofSeconds(60));
        service.create(sooner, recipient, NOW, Duration.ofSeconds(20));

        assertEquals(
                java.util.List.of(sooner, later),
                service.incomingRequests(recipient, NOW).stream().map(TeleportRequest::senderId).toList()
        );
    }
}
