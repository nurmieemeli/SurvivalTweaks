package gg.nurmi.survivaltweaks;

import gg.nurmi.survivaltweaks.command.SurvivalTweaksCommand;
import gg.nurmi.survivaltweaks.command.lock.LockCommand;
import gg.nurmi.survivaltweaks.command.teleport.TeleportCommand;
import gg.nurmi.survivaltweaks.service.MaintenanceService;
import gg.nurmi.survivaltweaks.service.ReleaseUpdateService;
import gg.nurmi.survivaltweaks.service.SafeTeleportService;
import gg.nurmi.survivaltweaks.ui.SocialProfileController;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionContractTest {

    @Test
    void everyCommandPermissionIsDeclaredAndEveryDeclarationIsDocumented() {
        YamlConfiguration descriptor = descriptor();
        ConfigurationSection commands =
                Objects.requireNonNull(descriptor.getConfigurationSection("commands"));
        ConfigurationSection permissions =
                Objects.requireNonNull(descriptor.getConfigurationSection("permissions"));

        for (String command : commands.getKeys(false)) {
            String permission = commands.getString(command + ".permission");
            if (permission != null) {
                assertTrue(permissions.contains(permission), command + " -> " + permission);
            }
        }
        Set<String> declared = permissions.getValues(true).keySet().stream()
                .filter(path -> path.endsWith(".description"))
                .map(path -> path.substring(0, path.length() - ".description".length()))
                .collect(java.util.stream.Collectors.toSet());
        for (String permission : declared) {
            assertFalse(permission.contains("*"), "wildcard permission: " + permission);
            assertFalse(
                    Objects.requireNonNullElse(
                            permissions.getString(permission + ".description"),
                            ""
                    ).isBlank(),
                    "missing description: " + permission
            );
            assertNotNull(
                    permissions.get(permission + ".default"),
                    "missing default: " + permission
            );
        }
        assertFalse(declared.isEmpty(), "no permission declarations found");
    }

    @Test
    void everyPermissionUsedOutsideCommandMetadataIsDeclared() {
        ConfigurationSection permissions = Objects.requireNonNull(
                descriptor().getConfigurationSection("permissions")
        );
        Set<String> runtimePermissions = Set.copyOf(List.of(
                SurvivalTweaksCommand.RELOAD_PERMISSION,
                SurvivalTweaksCommand.DOCTOR_PERMISSION,
                SurvivalTweaksCommand.PERFORMANCE_PERMISSION,
                SurvivalTweaksCommand.SPAWN_POOL_PERMISSION,
                SurvivalTweaksCommand.BACKUP_PERMISSION,
                SurvivalTweaksCommand.MAINTENANCE_PERMISSION,
                SurvivalTweaksCommand.ENCHANT_PERMISSION,
                LockCommand.ADMIN_PERMISSION,
                TeleportCommand.BYPASS_PERMISSION,
                SafeTeleportService.INSTANT_PERMISSION,
                SocialProfileController.BYPASS_PERMISSION,
                MaintenanceService.BYPASS_PERMISSION,
                ReleaseUpdateService.NOTIFY_PERMISSION
        ));

        runtimePermissions.forEach(permission ->
                assertTrue(permissions.contains(permission), "undeclared runtime permission: " + permission)
        );
    }

    private YamlConfiguration descriptor() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("plugin.yml")),
                StandardCharsets.UTF_8
        ));
    }
}
