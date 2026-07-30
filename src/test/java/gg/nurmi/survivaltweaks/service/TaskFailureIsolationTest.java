package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskFailureIsolationTest {

    @Test
    void recordsFailuresAndAllowsLaterRunsToContinue() {
        TaskFailureIsolation isolation = new TaskFailureIsolation(
                quietLogger(),
                Clock.systemUTC()
        );
        AtomicInteger completed = new AtomicInteger();

        isolation.run("test subsystem", () -> {
            throw new IllegalStateException("expected");
        });
        isolation.run("test subsystem", completed::incrementAndGet);

        assertEquals(1, completed.get());
        TaskFailureIsolation.Failure failure = isolation
                .recent(Duration.ofMinutes(1))
                .getFirst();
        assertEquals("test subsystem", failure.subsystem());
        assertEquals(1L, failure.count());
        assertEquals("IllegalStateException", failure.problem());
    }

    private Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
    }
}
