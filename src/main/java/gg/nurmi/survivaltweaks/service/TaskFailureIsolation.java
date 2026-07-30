package gg.nurmi.survivaltweaks.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TaskFailureIsolation {

    private static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    private final Logger logger;
    private final Clock clock;
    private final ConcurrentMap<String, Failure> failures = new ConcurrentHashMap<>();

    public TaskFailureIsolation(Logger logger, Clock clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Runnable guard(String subsystem, Runnable action) {
        Objects.requireNonNull(action, "action");
        return () -> run(subsystem, action);
    }

    public void run(String subsystem, Runnable action) {
        try {
            action.run();
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable throwable) {
            Instant now = clock.instant();
            Failure failure = failures.compute(
                    subsystem,
                    (ignored, previous) -> previous == null
                            ? new Failure(subsystem, 1L, now, now, throwable.getClass().getSimpleName())
                            : new Failure(
                                    subsystem,
                                    previous.count() + 1L,
                                    now,
                                    previous.lastLoggedAt(),
                                    throwable.getClass().getSimpleName()
                            )
            );
            if (failure.lastLoggedAt().plus(LOG_INTERVAL).isAfter(now)
                    && failure.count() > 1L) {
                return;
            }
            failures.computeIfPresent(subsystem, (ignored, current) ->
                    new Failure(
                            current.subsystem(),
                            current.count(),
                            current.lastFailureAt(),
                            now,
                            current.problem()
                    )
            );
            logger.log(
                    Level.SEVERE,
                    "Isolated failure in recurring subsystem '" + subsystem
                            + "'; the task will continue on its next scheduled run.",
                    throwable
            );
        }
    }

    public List<Failure> recent(Duration maximumAge) {
        Instant cutoff = clock.instant().minus(maximumAge);
        return failures.values().stream()
                .filter(failure -> !failure.lastFailureAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Failure::lastFailureAt).reversed())
                .toList();
    }

    public record Failure(
            String subsystem,
            long count,
            Instant lastFailureAt,
            Instant lastLoggedAt,
            String problem
    ) {
    }
}
