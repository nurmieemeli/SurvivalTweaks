package gg.nurmi.survivaltweaks.ui;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import gg.nurmi.survivaltweaks.service.FeedbackService;
import gg.nurmi.survivaltweaks.service.MessageService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ConfirmationDialogServiceTest {

    @Test
    void disabledDialogsLeaveCommandFallbackInControl() {
        Player player = mock(Player.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("ui.dialogs-enabled", false);
        ConfirmationDialogService dialogs = new ConfirmationDialogService(
                mock(JavaPlugin.class),
                mock(MessageService.class),
                mock(FeedbackService.class),
                new SettingsService(PluginSettings.load(
                        config,
                        java.util.logging.Logger.getAnonymousLogger()
                ))
        );

        assertFalse(dialogs.showUnlock(player, ignored -> {
            throw new AssertionError("disabled callback ran");
        }));
        verify(player, never()).showDialog(org.mockito.ArgumentMatchers.any());
    }
}
