package gg.nurmi.survivaltweaks.service;

import gg.nurmi.survivaltweaks.object.LanguagePreference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.UUID;
import java.util.function.Function;

public final class MessageService {

    private static final Locale FINNISH_LOCALE = Locale.forLanguageTag("fi-FI");
    private static final Locale ENGLISH_LOCALE = Locale.US;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private volatile Map<String, String> englishTemplates;
    private volatile Map<String, String> finnishTemplates;
    private final Logger logger;
    private final boolean migratedLegacyMessages;
    private volatile Function<UUID, LanguagePreference> languagePreference =
            ignored -> LanguagePreference.AUTO;

    public MessageService(JavaPlugin plugin, FileConfiguration config, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        YamlConfiguration english = loadCatalog(plugin, "messages_en.yml");
        YamlConfiguration finnish = loadCatalog(plugin, "messages_fi.yml");
        this.migratedLegacyMessages = migrateLegacyFinnishMessages(
                config,
                finnish,
                new File(plugin.getDataFolder(), "messages_fi.yml")
        );
        this.englishTemplates = loadTemplates(english);
        this.finnishTemplates = loadTemplates(finnish);
    }

    MessageService(
            Map<String, String> englishTemplates,
            Map<String, String> finnishTemplates,
            Logger logger
    ) {
        this.plugin = null;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.englishTemplates = Map.copyOf(englishTemplates);
        this.finnishTemplates = Map.copyOf(finnishTemplates);
        this.migratedLegacyMessages = false;
    }

    public void send(Audience audience, String key, TagResolver... placeholders) {
        audience.sendMessage(component(audience, key, placeholders));
    }

    /**
     * Picks the singular sibling {@code <key>-one} when a count is exactly one.
     * Languages such as Finnish inflect the counted noun, so "1 unread
     * notification" and "2 unread notifications" cannot share one template.
     */
    public static String plural(String key, long count) {
        return count == 1L ? key + "-one" : key;
    }

    public Component component(String key, TagResolver... placeholders) {
        return component(null, key, placeholders);
    }

    public Component component(Audience audience, String key, TagResolver... placeholders) {
        Map<String, String> english = englishTemplates;
        Map<String, String> selectedTemplates = templatesFor(audience, english, finnishTemplates);
        String template = selectedTemplates.get(key);
        if (template == null && selectedTemplates != english) {
            template = english.get(key);
        }
        if (template == null) {
            return miniMessage.deserialize(
                    "<red>Missing message: <key>",
                    Placeholder.unparsed("key", key)
            );
        }

        try {
            return miniMessage.deserialize(template, placeholders);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Invalid MiniMessage template at messages." + key, exception);
            return Component.text(template);
        }
    }

    public String plain(Audience audience, String key, TagResolver... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(component(audience, key, placeholders));
    }

    public boolean migratedLegacyMessages() {
        return migratedLegacyMessages;
    }

    public void languagePreference(Function<UUID, LanguagePreference> provider) {
        languagePreference = Objects.requireNonNull(provider, "provider");
    }

    public Prepared prepareReload() throws IOException, InvalidConfigurationException {
        if (plugin == null) {
            throw new IllegalStateException("This message service is not attached to a plugin");
        }
        Map<String, String> english = loadReloadCatalog("messages_en.yml");
        Map<String, String> finnish = loadReloadCatalog("messages_fi.yml");
        validateTemplates(english, "messages_en.yml");
        validateTemplates(finnish, "messages_fi.yml");
        return new Prepared(english, finnish);
    }

    public void apply(Prepared prepared) {
        englishTemplates = prepared.english;
        finnishTemplates = prepared.finnish;
    }

    public Prepared snapshot() {
        return new Prepared(englishTemplates, finnishTemplates);
    }

    /**
     * The locale matching the catalog this audience reads, so numbers are
     * grouped and separated the same way as the words around them.
     */
    public Locale locale(Audience audience) {
        return readsFinnish(audience) ? FINNISH_LOCALE : ENGLISH_LOCALE;
    }

    private boolean readsFinnish(Audience audience) {
        if (!(audience instanceof Player player)) {
            return false;
        }
        LanguagePreference preference = languagePreference.apply(player.getUniqueId());
        return preference == LanguagePreference.FINNISH
                || (preference == LanguagePreference.AUTO
                && player.locale().getLanguage().equalsIgnoreCase("fi"));
    }

    private Map<String, String> templatesFor(
            Audience audience,
            Map<String, String> english,
            Map<String, String> finnish
    ) {
        return readsFinnish(audience) ? finnish : english;
    }

    private YamlConfiguration loadCatalog(JavaPlugin plugin, String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.isFile()) {
            plugin.saveResource(resourceName, false);
        }

        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(
                        plugin.getResource(resourceName),
                        "Missing bundled resource " + resourceName
                ),
                StandardCharsets.UTF_8
        ));
        YamlConfiguration catalog = YamlConfiguration.loadConfiguration(file);
        boolean missingDefaults = defaults.getKeys(true).stream()
                .anyMatch(path -> !catalog.contains(path, true));
        catalog.setDefaults(defaults);
        catalog.options().copyDefaults(true);
        if (missingDefaults) {
            saveCatalog(catalog, file);
        }
        return catalog;
    }

    private Map<String, String> loadReloadCatalog(
            String resourceName
    ) throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(plugin.getResource(resourceName), "Missing bundled resource " + resourceName),
                StandardCharsets.UTF_8
        ));
        YamlConfiguration catalog = new YamlConfiguration();
        catalog.load(new File(plugin.getDataFolder(), resourceName));
        catalog.setDefaults(defaults);
        catalog.options().copyDefaults(true);
        return loadTemplates(catalog);
    }

    private void validateTemplates(Map<String, String> templates, String resourceName) {
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            try {
                miniMessage.deserialize(entry.getValue());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        resourceName + " contains invalid MiniMessage at " + entry.getKey(),
                        exception
                );
            }
        }
    }

    private boolean migrateLegacyFinnishMessages(
            FileConfiguration config,
            YamlConfiguration finnish,
            File file
    ) {
        ConfigurationSection legacy = config.getConfigurationSection("messages");
        if (legacy == null) {
            return false;
        }

        for (String key : legacy.getKeys(true)) {
            if (legacy.isString(key)) {
                finnish.set(key, legacy.getString(key));
            }
        }
        if (!saveCatalog(finnish, file)) {
            logger.warning("Kept legacy messages in config.yml because messages_fi.yml could not be saved.");
            return false;
        }
        config.set("messages", null);
        logger.info("Migrated legacy Finnish messages from config.yml to messages_fi.yml.");
        return true;
    }

    private boolean saveCatalog(YamlConfiguration catalog, File file) {
        try {
            catalog.save(file);
            return true;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not save message catalog " + file.getName(), exception);
            return false;
        }
    }

    private Map<String, String> loadTemplates(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }

        Map<String, String> loaded = new HashMap<>();
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) {
                loaded.put(key, Objects.requireNonNull(section.getString(key)));
            }
        }
        return Map.copyOf(loaded);
    }

    public static final class Prepared {

        private final Map<String, String> english;
        private final Map<String, String> finnish;

        private Prepared(Map<String, String> english, Map<String, String> finnish) {
            this.english = english;
            this.finnish = finnish;
        }
    }
}
