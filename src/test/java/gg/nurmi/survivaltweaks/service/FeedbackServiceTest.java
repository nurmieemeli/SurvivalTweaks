package gg.nurmi.survivaltweaks.service;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackServiceTest {

    @Test
    void configuredCuePlaysSoundAndParticleForOnlyThatPlayer() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("feedback.enabled", true);
        config.set("feedback.sounds-enabled", true);
        config.set("feedback.particles-enabled", true);
        config.set("feedback.cues.test.sound", "minecraft:ui.button.click");
        config.set("feedback.cues.test.volume", 0.5);
        config.set("feedback.cues.test.pitch", 1.25);
        config.set("feedback.cues.test.particle", "HAPPY_VILLAGER");
        config.set("feedback.cues.test.count", 7);
        config.set("feedback.cues.test.spread", 0.4);

        Player player = mock(Player.class);
        Location location = new Location(mock(World.class), 1, 2, 3);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(location);

        new FeedbackService(config, quietLogger()).play(player, "test");

        verify(player).playSound(
                location,
                "minecraft:ui.button.click",
                SoundCategory.PLAYERS,
                0.5F,
                1.25F
        );
        verify(player).spawnParticle(
                Particle.HAPPY_VILLAGER,
                location.clone().add(0, 1, 0),
                7,
                0.4,
                0.4,
                0.4,
                0
        );
    }

    @Test
    void globallyDisabledFeedbackDoesNothing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("feedback.enabled", false);
        Player player = mock(Player.class);

        new FeedbackService(config, quietLogger()).play(player, "anything");

        verify(player, never()).getLocation();
    }

    @Test
    void reloadPreparationRejectsInvalidParticleWithoutChangingCurrentFeedback() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("feedback.enabled", false);
        FeedbackService feedback = new FeedbackService(current, quietLogger());
        YamlConfiguration invalid = new YamlConfiguration();
        invalid.set("feedback.enabled", true);
        invalid.set("feedback.sounds-enabled", true);
        invalid.set("feedback.particles-enabled", true);
        invalid.set("feedback.cues.test.particle", "NOT_A_PARTICLE");

        assertThrows(IllegalArgumentException.class, () -> feedback.prepare(invalid));

        Player player = mock(Player.class);
        feedback.play(player, "test");
        verify(player, never()).getLocation();
    }

    private Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }
}
