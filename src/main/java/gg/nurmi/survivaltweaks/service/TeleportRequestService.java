package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.TeleportRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TeleportRequestService {

    private final Map<UUID, TeleportRequest> requestsBySender = new HashMap<>();

    public Optional<TeleportRequest> create(
            UUID senderId,
            UUID recipientId,
            Instant now,
            Duration lifetime
    ) {
        purgeExpired(now);
        if (requestsBySender.containsKey(senderId)) {
            return Optional.empty();
        }

        TeleportRequest request = new TeleportRequest(senderId, recipientId, now.plus(lifetime));
        requestsBySender.put(senderId, request);
        return Optional.of(request);
    }

    public boolean hasOutgoingRequest(UUID senderId, Instant now) {
        purgeExpired(now);
        return requestsBySender.containsKey(senderId);
    }

    public List<TeleportRequest> incomingRequests(UUID recipientId, Instant now) {
        purgeExpired(now);
        return requestsBySender.values().stream()
                .filter(request -> request.recipientId().equals(recipientId))
                .sorted(Comparator.comparing(TeleportRequest::expiresAt))
                .toList();
    }

    public boolean consume(TeleportRequest request) {
        return requestsBySender.remove(request.senderId(), request);
    }

    public void removeForPlayer(UUID uniqueId) {
        requestsBySender.entrySet().removeIf(entry ->
                entry.getKey().equals(uniqueId) || entry.getValue().recipientId().equals(uniqueId)
        );
    }

    public int purgeExpired(Instant now) {
        return removeExpired(now).size();
    }

    public List<TeleportRequest> removeExpired(Instant now) {
        List<TeleportRequest> expired = requestsBySender.values().stream()
                .filter(request -> request.isExpired(now))
                .toList();
        expired.forEach(this::consume);
        return expired;
    }

    int size() {
        return requestsBySender.size();
    }
}
