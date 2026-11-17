package com.jifercraft.bingo.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jifercraft.bingo.BingoRush;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

public final class UpdateChecker implements Listener {

    public enum CheckResult {
        UPDATE_FOUND,
        UP_TO_DATE,
        NOT_FOUND,
        RATE_LIMITED,
        ERROR
    }

    private static final String GITHUB_REPO = "JainaGam3r45/BingoRush";
    private static final String RELEASES_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String TAGS_API = "https://api.github.com/repos/" + GITHUB_REPO + "/tags?per_page=100";
    private static final String RELEASES_URL = "https://github.com/" + GITHUB_REPO + "/releases";

    private final BingoRush plugin;
    private final Version currentVersion;
    private final HttpClient httpClient;

    private BukkitTask periodicTask;
    private volatile Version latestVersion;
    private volatile String downloadUrl = RELEASES_URL;
    private volatile boolean updateAvailable;
    private CompletableFuture<CheckResult> inFlight;

    public UpdateChecker(BingoRush plugin) {
        this(plugin, Version.parse(plugin.getPluginMeta().getVersion()));
    }

    UpdateChecker(BingoRush plugin, Version currentVersion) {
        this.plugin = plugin;
        this.currentVersion = currentVersion;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<CheckResult> check() {
        return check(null);
    }

    /**
     * Checks GitHub for a newer release, or the highest tag while no stable
     * release exists, off the main thread. With a sender, they get a reply
     * either way; a sender who asks while a check is already running gets the
     * result of that one instead of a second request.
     */
    public CompletableFuture<CheckResult> check(CommandSender feedback) {
        if (feedback != null) {
            plugin.messages().send(feedback, "update-checking");
        }
        CompletableFuture<CheckResult> running;
        boolean started = false;
        synchronized (this) {
            if (inFlight == null) {
                inFlight = query();
                started = true;
            }
            running = inFlight;
        }
        if (started) {
            running.whenComplete((result, error) -> {
                synchronized (this) {
                    inFlight = null;
                }
            });
            running.thenAccept(result -> onMainThread(() -> {
                announce(result, feedback);
                reply(result, feedback);
            }));
        } else if (feedback != null) {
            running.thenAccept(result -> onMainThread(() -> reply(result, feedback)));
        }
        return running;
    }

    private CompletableFuture<CheckResult> query() {
        return httpClient.sendAsync(buildRequest(RELEASES_API), HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status == 200) {
                        return CompletableFuture.completedFuture(handleReleaseJson(response.body()));
                    }
                    if (status == 404) {
                        // releases/latest skips pre-releases, so while every
                        // release is one the tags are the only source.
                        return httpClient.sendAsync(buildRequest(TAGS_API), HttpResponse.BodyHandlers.ofString())
                                .thenApply(tags -> switch (tags.statusCode()) {
                                    case 200 -> handleTagsJson(tags.body());
                                    case 404 -> CheckResult.NOT_FOUND;
                                    default -> statusResult(tags.statusCode());
                                });
                    }
                    return CompletableFuture.completedFuture(statusResult(status));
                })
                .exceptionally(error -> CheckResult.ERROR);
    }

    // GitHub answers an exhausted rate limit with 403 or 429, depending on the limit hit.
    private static CheckResult statusResult(int status) {
        return status == 403 || status == 429 ? CheckResult.RATE_LIMITED : CheckResult.ERROR;
    }

    private HttpRequest buildRequest(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("User-Agent", "BingoRush-Updater")
                .header("Accept", "application/vnd.github.v3+json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }

    private CheckResult handleReleaseJson(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return CheckResult.ERROR;
            }
            JsonObject json = root.getAsJsonObject();
            if (!json.has("tag_name")) {
                return CheckResult.NOT_FOUND;
            }
            String tagName = json.get("tag_name").getAsString();
            String url = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_URL;
            return processDiscoveredVersion(tagName, url);
        } catch (Exception e) {
            return CheckResult.ERROR;
        }
    }

    CheckResult handleTagsJson(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) {
                return CheckResult.ERROR;
            }
            // GitHub does not promise any order for this list, so take the highest version.
            String bestTag = null;
            Version best = null;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject() || !element.getAsJsonObject().has("name")) {
                    continue;
                }
                String tagName = element.getAsJsonObject().get("name").getAsString();
                Version version = Version.parse(tagName);
                if (best == null || version.isNewerThan(best)) {
                    best = version;
                    bestTag = tagName;
                }
            }
            if (bestTag == null) {
                return CheckResult.NOT_FOUND;
            }
            String url = "https://github.com/" + GITHUB_REPO + "/releases/tag/" + bestTag;
            return processDiscoveredVersion(bestTag, url);
        } catch (Exception e) {
            return CheckResult.ERROR;
        }
    }

    private CheckResult processDiscoveredVersion(String tagName, String url) {
        Version remote = Version.parse(tagName);
        this.downloadUrl = url;
        if (remote.isNewerThan(currentVersion)) {
            this.latestVersion = remote;
            this.updateAvailable = true;
            return CheckResult.UPDATE_FOUND;
        } else {
            this.updateAvailable = false;
            return CheckResult.UP_TO_DATE;
        }
    }

    // A check can finish after the plugin is disabled, and the scheduler
    // refuses tasks from a disabled plugin.
    private void onMainThread(Runnable task) {
        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** The console and online admins hear about a new version, unless the notifier is off. */
    private void announce(CheckResult result, CommandSender asker) {
        if (result == CheckResult.RATE_LIMITED) {
            plugin.getLogger().warning("GitHub API rate limit exceeded while checking for updates.");
            return;
        }
        if (result != CheckResult.UPDATE_FOUND
                || (plugin.bingoConfig() != null && !plugin.bingoConfig().updateNotifierEnabled())) {
            return;
        }
        plugin.messages().console("A new version is available: <ok>v<latest></ok> (current: <muted>v<current></muted>)",
                Placeholder.unparsed("latest", latestVersion.clean()),
                Placeholder.unparsed("current", currentVersion.clean()));
        plugin.messages().console("Download it at <hl><url></hl>", Placeholder.unparsed("url", downloadUrl));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != asker && (player.hasPermission("bingo.admin") || player.hasPermission("bingo.update"))) {
                sendUpdateNotification(player);
            }
        }
    }

    /** Whoever ran /bingo version gets an answer, even with the notifier off. */
    private void reply(CheckResult result, CommandSender asker) {
        if (asker == null || !isSenderReachable(asker)) {
            return;
        }
        switch (result) {
            case UPDATE_FOUND -> sendUpdateNotification(asker);
            case RATE_LIMITED -> plugin.messages().send(asker, "update-rate-limited");
            case NOT_FOUND -> plugin.messages().send(asker, "update-not-found");
            case UP_TO_DATE -> plugin.messages().send(asker, "update-up-to-date",
                    Placeholder.unparsed("current", currentVersion.clean()));
            case ERROR -> plugin.messages().send(asker, "update-error");
        }
    }

    private boolean isSenderReachable(CommandSender sender) {
        if (sender instanceof Player p) {
            return p.isOnline();
        }
        return true;
    }

    public void startPeriodicCheck() {
        stopPeriodicCheck();
        if (plugin == null || plugin.bingoConfig() == null || !plugin.bingoConfig().updateNotifierEnabled()) {
            return;
        }
        int intervalHours = plugin.bingoConfig().updateCheckIntervalHours();
        if (intervalHours <= 0) {
            return;
        }
        long intervalTicks = (long) intervalHours * 60L * 60L * 20L;
        periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(null), intervalTicks, intervalTicks);
    }

    public void stopPeriodicCheck() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable || latestVersion == null) {
            return;
        }
        if (plugin.bingoConfig() != null && !plugin.bingoConfig().updateNotifierOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("bingo.admin") && !player.hasPermission("bingo.update")) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendUpdateNotification(player);
            }
        }, 40L);
    }

    public void sendUpdateNotification(CommandSender target) {
        if (latestVersion == null) {
            return;
        }
        plugin.messages().send(target, "update-available",
                Placeholder.unparsed("latest", latestVersion.clean()),
                Placeholder.unparsed("current", currentVersion.clean()),
                Placeholder.unparsed("url", downloadUrl),
                Placeholder.parsed("link", clickableLink(downloadUrl)));
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public Version latestVersion() {
        return latestVersion;
    }

    // The click target is parsed as part of the tag, so it must be a plain
    // GitHub link: a tag name can contain "<", and an odd one gets the
    // generic releases page instead of a way into the message.
    static String clickableLink(String url) {
        return url.matches("https://github\\.com/[A-Za-z0-9._/-]+") ? url : RELEASES_URL;
    }

    public Version currentVersion() {
        return currentVersion;
    }

    public String downloadUrl() {
        return downloadUrl;
    }
}
