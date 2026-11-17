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
    private volatile boolean checking;

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
     * Checks for new releases or tags on GitHub asynchronously.
     * If feedback is provided, sends progress and result messages to the sender.
     */
    public CompletableFuture<CheckResult> check(CommandSender feedback) {
        if (checking) {
            if (feedback != null) {
                plugin.messages().send(feedback, "update-checking");
            }
            return CompletableFuture.completedFuture(updateAvailable ? CheckResult.UPDATE_FOUND : CheckResult.UP_TO_DATE);
        }

        checking = true;
        if (feedback != null) {
            plugin.messages().send(feedback, "update-checking");
        }

        HttpRequest releasesRequest = buildRequest(RELEASES_API);
        return httpClient.sendAsync(releasesRequest, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status == 200) {
                        return CompletableFuture.completedFuture(handleReleaseJson(response.body()));
                    }
                    if (status == 404) {
                        // Fallback to tags if no formal GitHub release exists yet
                        HttpRequest tagsRequest = buildRequest(TAGS_API);
                        return httpClient.sendAsync(tagsRequest, HttpResponse.BodyHandlers.ofString())
                                .thenApply(tagsResponse -> {
                                    int tagsStatus = tagsResponse.statusCode();
                                    if (tagsStatus == 200) {
                                        return handleTagsJson(tagsResponse.body());
                                    }
                                    if (tagsStatus == 403) {
                                        return CheckResult.RATE_LIMITED;
                                    }
                                    if (tagsStatus == 404) {
                                        return CheckResult.NOT_FOUND;
                                    }
                                    return CheckResult.ERROR;
                                });
                    }
                    if (status == 403) {
                        return CompletableFuture.completedFuture(CheckResult.RATE_LIMITED);
                    }
                    return CompletableFuture.completedFuture(CheckResult.ERROR);
                })
                .exceptionally(ex -> CheckResult.ERROR)
                .thenApply(result -> {
                    checking = false;
                    handleCheckResult(result, feedback);
                    return result;
                });
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

    private void handleCheckResult(CheckResult result, CommandSender feedback) {
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (result) {
                case UPDATE_FOUND -> {
                    if (plugin.bingoConfig() == null || plugin.bingoConfig().updateNotifierEnabled()) {
                        plugin.getLogger().info("A new version is available: v" + latestVersion.clean()
                                + " (current: v" + currentVersion.clean() + ")");
                        plugin.getLogger().info("Download it at: " + downloadUrl);

                        for (Player player : Bukkit.getOnlinePlayers()) {
                            if (player.hasPermission("bingo.admin") || player.hasPermission("bingo.update")) {
                                sendUpdateNotification(player);
                            }
                        }
                    }
                }
                case RATE_LIMITED -> {
                    plugin.getLogger().warning("GitHub API rate limit exceeded while checking for updates.");
                    if (feedback != null && isSenderReachable(feedback)) {
                        plugin.messages().send(feedback, "update-rate-limited");
                    }
                }
                case NOT_FOUND -> {
                    if (feedback != null && isSenderReachable(feedback)) {
                        plugin.messages().send(feedback, "update-not-found");
                    }
                }
                case UP_TO_DATE -> {
                    if (feedback != null && isSenderReachable(feedback)) {
                        plugin.messages().send(feedback, "update-up-to-date",
                                Placeholder.unparsed("current", currentVersion.clean()));
                    }
                }
                case ERROR -> {
                    if (feedback != null && isSenderReachable(feedback)) {
                        plugin.messages().send(feedback, "update-error");
                    }
                }
            }
        });
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
                Placeholder.unparsed("url", downloadUrl));
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public Version latestVersion() {
        return latestVersion;
    }

    public Version currentVersion() {
        return currentVersion;
    }

    public String downloadUrl() {
        return downloadUrl;
    }
}
