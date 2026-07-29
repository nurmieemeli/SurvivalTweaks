package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.config.PluginSettings;
import gg.nurmi.survivaltweaks.config.SettingsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomDeathMessageServiceTest {

    private final Server server = mock(Server.class);
    private final MessageService messages = mock(MessageService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final PluginSettings current = mock(PluginSettings.class);
    private final RandomGenerator random = mock(RandomGenerator.class);
    private final PlayerDeathEvent event = mock(PlayerDeathEvent.class);
    private final Player deadPlayer = mock(Player.class);
    private final Player visiblePlayer = mock(Player.class);
    private final Player hiddenFromPlayer = mock(Player.class);
    private final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    private final EntityDamageEvent damage = mock(EntityDamageEvent.class);
    private final DamageSource source = mock(DamageSource.class);
    private final CustomDeathMessageService service =
            new CustomDeathMessageService(server, messages, settings, random);

    @BeforeEach
    void setUp() {
        when(settings.current()).thenReturn(current);
        when(current.customDeathMessagesEnabled()).thenReturn(true);
        when(current.customDeathMessageRareVariantPercent()).thenReturn(5);
        when(current.customDeathMessageCauses()).thenReturn(Set.of("fall"));
        when(event.getShowDeathMessages()).thenReturn(true);
        when(event.deathMessage()).thenReturn(Component.text("vanilla"));
        when(event.getEntity()).thenReturn(deadPlayer);
        when(event.getDamageSource()).thenReturn(source);
        when(deadPlayer.getLastDamageCause()).thenReturn(damage);
        when(deadPlayer.getName()).thenReturn("Alex");
        when(damage.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        doReturn(List.of(visiblePlayer, hiddenFromPlayer)).when(server).getOnlinePlayers();
        when(server.getConsoleSender()).thenReturn(console);
        when(visiblePlayer.canSee(deadPlayer)).thenReturn(true);
        when(hiddenFromPlayer.canSee(deadPlayer)).thenReturn(false);
        when(random.nextInt(100)).thenReturn(50);
        when(random.nextInt(2)).thenReturn(0);
    }

    @Test
    void localizesOneVariantPerViewerAndPreservesTheDeathScreen() {
        service.onDeath(event);

        verify(event).deathScreenMessageOverride(any());
        verify(event).setShowDeathMessages(false);
        verify(messages).send(
                eq(visiblePlayer),
                eq("death-messages.fall.1"),
                any(TagResolver[].class)
        );
        verify(messages).send(
                eq(console),
                eq("death-messages.fall.1"),
                any(TagResolver[].class)
        );
        verify(messages, never()).send(
                eq(hiddenFromPlayer),
                any(String.class),
                any(TagResolver[].class)
        );
    }

    @Test
    void leavesEntityAttributedDeathsEntirelyToVanilla() {
        when(source.getCausingEntity()).thenReturn(mock(Entity.class));

        service.onDeath(event);

        verify(event, never()).setShowDeathMessages(anyBoolean());
        verify(messages, never()).send(any(), any(String.class), any(TagResolver[].class));
    }
}
