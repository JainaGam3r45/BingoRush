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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateChecker implements Listener {

    private static final String GITHUB_REPO = "JainaGam3r45/BingoRush";
    private static final String RELEASES_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String RELEASES_URL = "https://github.com/" + GITHUB_REPO + "/releases";

    private final BingoRush plugin;
    private final Version currentVersion;
    private final HttpClient httpClient;

    private volatile Version latestVersion;
    private volatile String downloadUrl = RELEASES_URL;
    private volatile boolean updateAvailable;

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

    /**
     * Queries GitHub for the latest release. Runs asynchronously so server
     * boot or reloads never stall on an HTTP timeout.
     */
    public CompletableFuture<Boolean> check() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_API))
                .header("User-Agent", "BingoRush-Updater")
                .header("Accept", "application/vnd.github.v3+json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> handleResponse(response.statusCode(), response.body()))
                .exceptionally(ex -> false);
    }

    boolean handleResponse(int status, String body) {
        // 404 is normal before the first GitHub release is created or while
        // the repo is still private.
        if (status == 404 || status != 200 || body == null || body.isBlank()) {
            return false;
        }

        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return false;
            }
            JsonObject json = root.getAsJsonObject();
            if (!json.has("tag_name")) {
                return false;
            }

            String tagName = json.get("tag_name").getAsString();
            Version remote = Version.parse(tagName);
            if (json.has("html_url")) {
                downloadUrl = json.get("html_url").getAsString();
            }

            if (remote.isNewerThan(currentVersion)) {
                this.latestVersion = remote;
                this.updateAvailable = true;

                if (plugin != null && (plugin.bingoConfig() == null || plugin.bingoConfig().updateNotifierEnabled())) {
                    plugin.getLogger().info("A new version is available: " + tagName + " (current: " + currentVersion + ")");
                    plugin.getLogger().info("Download it at: " + downloadUrl);
                }
                return true;
            }
        } catch (Exception ignored) {
            // Broken json or unexpected structure from a proxy/firewall.
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable) {
            return;
        }
        if (plugin.bingoConfig() != null && !plugin.bingoConfig().updateNotifierOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("bingo.admin") && !player.hasPermission("bingo.update")) {
            return;
        }

        // Delay the message two seconds so it is not buried under client connection
        // lag, server MOTD or world spawn titles.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.messages().send(player, "update-available",
                        Placeholder.unparsed("latest", latestVersion.toString()),
                        Placeholder.unparsed("current", currentVersion.toString()),
                        Placeholder.unparsed("url", downloadUrl));
            }
        }, 40L);
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
