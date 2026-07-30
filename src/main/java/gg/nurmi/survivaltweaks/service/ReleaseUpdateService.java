package gg.nurmi.survivaltweaks.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReleaseUpdateService implements AutoCloseable {

    public static final String NOTIFY_PERMISSION = "survivaltweaks.update-notify";
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
            "\"browser_download_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    );

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final Clock clock;
    private final Logger logger;
    private final Supplier<java.util.concurrent.CompletableFuture<Release>> fetcher;
    private final AtomicBoolean checking = new AtomicBoolean();
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> notifiedVersions = new ConcurrentHashMap<>();
    private volatile Configuration configuration;
    private volatile Cache cache;
    private volatile boolean closed;

    public ReleaseUpdateService(
            JavaPlugin plugin,
            MessageService messages,
            FileConfiguration config,
            Clock clock
    ) {
        this(
                plugin,
                messages,
                config,
                clock,
                plugin.getLogger(),
                httpFetcher(
                        repositoryApi(plugin.getPluginMeta().getWebsite()),
                        plugin.getPluginMeta().getName(),
                        plugin.getPluginMeta().getVersion()
                )
        );
    }

    ReleaseUpdateService(
            JavaPlugin plugin,
            MessageService messages,
            FileConfiguration config,
            Clock clock,
            Logger logger,
            Supplier<java.util.concurrent.CompletableFuture<Release>> fetcher
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        reconfigure(config);
    }

    public void reconfigure(FileConfiguration config) {
        Objects.requireNonNull(config, "config");
        configuration = new Configuration(
                config.getBoolean("updates.enabled", true),
                Duration.ofHours(bounded(config.getInt("updates.check-interval-hours", 6), 1, 168)),
                bounded(config.getInt("updates.join-delay-ticks", 60), 0, 1_200)
        );
    }

    public void playerJoined(Player player) {
        Configuration current = configuration;
        if (closed || !current.enabled() || !player.hasPermission(NOTIFY_PERMISSION)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> checkFor(player),
                current.joinDelayTicks()
        );
    }

    private void checkFor(Player player) {
        if (closed || !player.isOnline() || !player.hasPermission(NOTIFY_PERMISSION)) {
            return;
        }
        Cache current = cache;
        Instant now = clock.instant();
        if (current != null && current.checkedAt().plus(configuration.interval()).isAfter(now)) {
            notifyIfNewer(player, current.release());
            return;
        }
        pendingPlayers.add(player.getUniqueId());
        startCheck();
    }

    private void startCheck() {
        if (closed || !checking.compareAndSet(false, true)) {
            return;
        }
        fetcher.get().whenComplete((release, failure) -> {
            checking.set(false);
            if (closed) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (failure == null) {
                    cache = new Cache(clock.instant(), release);
                } else {
                    logger.log(Level.FINE, "Could not check the latest SurvivalTweaks release", failure);
                }
                List<UUID> waiting = new ArrayList<>(pendingPlayers);
                pendingPlayers.removeAll(waiting);
                Cache available = cache;
                if (available == null) {
                    return;
                }
                waiting.stream()
                        .map(plugin.getServer()::getPlayer)
                        .filter(Objects::nonNull)
                        .forEach(player -> notifyIfNewer(player, available.release()));
            });
        });
    }

    private void notifyIfNewer(Player player, Release release) {
        if (!player.isOnline()
                || !player.hasPermission(NOTIFY_PERMISSION)
                || !isNewer(release.version(), plugin.getPluginMeta().getVersion())
                || release.version().equals(
                        notifiedVersions.put(player.getUniqueId(), release.version())
                )) {
            return;
        }
        Component link = Component.text("[Download]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(release.downloadUrl()))
                .hoverEvent(HoverEvent.showText(messages.component(player, "updates.open-hover")));
        messages.send(
                player,
                "updates.available",
                Placeholder.unparsed("current", plugin.getPluginMeta().getVersion()),
                Placeholder.unparsed("latest", release.version()),
                Placeholder.component("link", link)
        );
    }

    static boolean isNewer(String candidate, String current) {
        Version left = Version.parse(candidate);
        Version right = Version.parse(current);
        return left.compareTo(right) > 0;
    }

    static Release parseRelease(String json, String pluginName) {
        Pattern jarName = Pattern.compile(
                "\"name\"\\s*:\\s*\"" + Pattern.quote(pluginName) + "-([^\"\\\\]+)\\.jar\"",
                Pattern.CASE_INSENSITIVE
        );
        Matcher asset = jarName.matcher(json);
        while (asset.find()) {
            Matcher download = DOWNLOAD_PATTERN.matcher(json);
            download.region(asset.end(), Math.min(json.length(), asset.end() + 16_384));
            if (!download.find()) {
                continue;
            }
            String downloadUrl = unescape(download.group(1));
            URI parsed = URI.create(downloadUrl);
            if (!"https".equalsIgnoreCase(parsed.getScheme())) {
                throw new IllegalArgumentException("Release asset download URL must use HTTPS");
            }
            return new Release(asset.group(1), downloadUrl);
        }
        throw new IllegalArgumentException(
                "GitHub release does not contain a downloadable " + pluginName + "-<version>.jar"
        );
    }

    private static Supplier<java.util.concurrent.CompletableFuture<Release>> httpFetcher(
            URI latestRelease,
            String pluginName,
            String currentVersion
    ) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return () -> {
            HttpRequest request = HttpRequest.newBuilder(latestRelease)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", pluginName + "/" + currentVersion)
                    .GET()
                    .build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException(
                                    "GitHub releases API returned HTTP " + response.statusCode()
                            );
                        }
                        return parseRelease(response.body(), pluginName);
                    });
        };
    }

    static URI repositoryApi(String website) {
        URI repository = URI.create(Objects.requireNonNull(website, "plugin website").strip());
        String[] path = repository.getPath().replaceAll("^/|/$", "").split("/");
        if (!"https".equalsIgnoreCase(repository.getScheme())
                || !"github.com".equalsIgnoreCase(repository.getHost())
                || path.length != 2
                || path[0].isBlank()
                || path[1].isBlank()) {
            throw new IllegalArgumentException(
                    "plugin website must be an HTTPS GitHub repository URL"
            );
        }
        return URI.create(
                "https://api.github.com/repos/" + path[0] + "/" + path[1] + "/releases/latest"
        );
    }

    private static String unescape(String value) {
        return value.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void close() {
        closed = true;
        pendingPlayers.clear();
        notifiedVersions.clear();
    }

    record Release(String version, String downloadUrl) {
    }

    private record Cache(Instant checkedAt, Release release) {
    }

    private record Configuration(boolean enabled, Duration interval, int joinDelayTicks) {
    }

    private record Version(List<Integer> numbers, String qualifier) implements Comparable<Version> {

        static Version parse(String text) {
            String normalized = Objects.requireNonNullElse(text, "")
                    .strip()
                    .toLowerCase(Locale.ROOT);
            if (normalized.startsWith("v")) {
                normalized = normalized.substring(1);
            }
            String[] parts = normalized.split("-", 2);
            List<Integer> numbers = new ArrayList<>();
            for (String part : parts[0].split("\\.")) {
                try {
                    numbers.add(Integer.parseInt(part.replaceAll("[^0-9].*$", "")));
                } catch (NumberFormatException ignored) {
                    numbers.add(0);
                }
            }
            return new Version(List.copyOf(numbers), parts.length == 2 ? parts[1] : "");
        }

        @Override
        public int compareTo(Version other) {
            int length = Math.max(numbers.size(), other.numbers.size());
            for (int index = 0; index < length; index++) {
                int left = index < numbers.size() ? numbers.get(index) : 0;
                int right = index < other.numbers.size() ? other.numbers.get(index) : 0;
                int compared = Integer.compare(left, right);
                if (compared != 0) {
                    return compared;
                }
            }
            if (qualifier.isEmpty() != other.qualifier.isEmpty()) {
                return qualifier.isEmpty() ? 1 : -1;
            }
            return qualifier.compareTo(other.qualifier);
        }
    }
}
