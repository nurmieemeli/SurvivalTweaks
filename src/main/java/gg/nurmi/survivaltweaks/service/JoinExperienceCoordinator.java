package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.ui.WelcomeBackController;
import org.bukkit.entity.Player;

import java.util.Objects;

/** Coordinates join-time experiences so richer views replace duplicate summaries. */
public final class JoinExperienceCoordinator {

    private final NewPlayerSpawnService newPlayerSpawns;
    private final WelcomeBackController welcomeBack;
    private final ReleaseUpdateService releaseUpdates;
    private final SessionSummaryService sessionSummaries;
    private final OperationalHealthService operationalHealth;

    public JoinExperienceCoordinator(
            NewPlayerSpawnService newPlayerSpawns,
            WelcomeBackController welcomeBack,
            ReleaseUpdateService releaseUpdates,
            SessionSummaryService sessionSummaries,
            OperationalHealthService operationalHealth
    ) {
        this.newPlayerSpawns = Objects.requireNonNull(newPlayerSpawns, "newPlayerSpawns");
        this.welcomeBack = Objects.requireNonNull(welcomeBack, "welcomeBack");
        this.releaseUpdates = Objects.requireNonNull(releaseUpdates, "releaseUpdates");
        this.sessionSummaries = Objects.requireNonNull(sessionSummaries, "sessionSummaries");
        this.operationalHealth = Objects.requireNonNull(operationalHealth, "operationalHealth");
    }

    public void playerJoined(Player player, PlayerSessionService.Session session) {
        Objects.requireNonNull(session, "session");
        newPlayerSpawns.playerJoined(player);
        boolean welcomeScheduled = welcomeBack.playerJoined(player, session);
        sessionSummaries.playerJoined(player, !welcomeScheduled);
        releaseUpdates.playerJoined(player);
        operationalHealth.playerJoined(player);
    }

    public void playerDisconnected(Player player) {
        newPlayerSpawns.playerDisconnected(player.getUniqueId());
    }
}
