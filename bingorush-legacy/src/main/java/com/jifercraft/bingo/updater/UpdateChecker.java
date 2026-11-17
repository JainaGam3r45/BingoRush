package com.jifercraft.bingo.updater;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jifercraft.bingo.BingoRush;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Checks GitHub for a newer release, or the highest tag while no stable
 * release exists. Java 8 has no HttpClient, so the request is a plain
 * connection on a Bukkit async task; results come back on the main thread.
 */
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
    private BukkitTask periodicTask;
    private volatile Version latestVersion;
    private volatile String downloadUrl = RELEASES_URL;
    private volatile boolean updateAvailable;
    // Senders waiting on the check that is already running; main thread only.
    private List<CommandSender> waiting;

    public UpdateChecker(BingoRush plugin) {
        this.plugin = plugin;
        this.currentVersion = Version.parse(plugin.getDescription().getVersion());
    }

    /**
     * Starts a check, or joins the one already running. With a sender, they
     * get an answer either way. Call from the main thread.
     */
    public void check(CommandSender feedback) {
        if (feedback != null) {
            plugin.messages().send(feedback, "update-checking");
        }
        if (waiting != null) {
            if (feedback != null) {
                waiting.add(feedback);
            }
            return;
        }
        waiting = new ArrayList<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CheckResult result = query();
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<CommandSender> askers = waiting;
                waiting = null;
                announce(result, feedback);
                reply(result, feedback);
                for (CommandSender asker : askers) {
                    reply(result, asker);
                }
            });
        });
    }

    private CheckResult query() {
        try {
            Response releases = get(RELEASES_API);
            if (releases.status == 200) {
                JsonElement root = new JsonParser().parse(releases.body);
                if (!root.isJsonObject() || !root.getAsJsonObject().has("tag_name")) {
                    return CheckResult.NOT_FOUND;
                }
                JsonObject json = root.getAsJsonObject();
                String url = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_URL;
                return discovered(json.get("tag_name").getAsString(), url);
            }
            if (releases.status != 404) {
                return statusResult(releases.status);
            }
            // releases/latest skips pre-releases, so while every release is one
            // the tags are the only source.
            Response tags = get(TAGS_API);
            if (tags.status == 404) {
                return CheckResult.NOT_FOUND;
            }
            if (tags.status != 200) {
                return statusResult(tags.status);
            }
            JsonElement root = new JsonParser().parse(tags.body);
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
                String tag = element.getAsJsonObject().get("name").getAsString();
                Version version = Version.parse(tag);
                if (best == null || version.isNewerThan(best)) {
                    best = version;
                    bestTag = tag;
                }
            }
            if (bestTag == null) {
                return CheckResult.NOT_FOUND;
            }
            return discovered(bestTag, "https://github.com/" + GITHUB_REPO + "/releases/tag/" + bestTag);
        } catch (IOException | RuntimeException e) {
            return CheckResult.ERROR;
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "BingoRush-Updater");
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                return new Response(status, "");
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return new Response(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    // GitHub answers an exhausted rate limit with 403 or 429, depending on the limit hit.
    private static CheckResult statusResult(int status) {
        return status == 403 || status == 429 ? CheckResult.RATE_LIMITED : CheckResult.ERROR;
    }

    private CheckResult discovered(String tag, String url) {
        Version remote = Version.parse(tag);
        downloadUrl = url;
        if (remote.isNewerThan(currentVersion)) {
            latestVersion = remote;
            updateAvailable = true;
            return CheckResult.UPDATE_FOUND;
        }
        updateAvailable = false;
        return CheckResult.UP_TO_DATE;
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
        if (asker == null || (asker instanceof Player && !((Player) asker).isOnline())) {
            return;
        }
        switch (result) {
            case UPDATE_FOUND:
                sendUpdateNotification(asker);
                break;
            case RATE_LIMITED:
                plugin.messages().send(asker, "update-rate-limited");
                break;
            case NOT_FOUND:
                plugin.messages().send(asker, "update-not-found");
                break;
            case UP_TO_DATE:
                plugin.messages().send(asker, "update-up-to-date", Placeholder.unparsed("current", currentVersion.clean()));
                break;
            default:
                plugin.messages().send(asker, "update-error");
        }
    }

    public void startPeriodicCheck() {
        stopPeriodicCheck();
        if (plugin.bingoConfig() == null || !plugin.bingoConfig().updateNotifierEnabled()) {
            return;
        }
        int intervalHours = plugin.bingoConfig().updateCheckIntervalHours();
        if (intervalHours <= 0) {
            return;
        }
        long intervalTicks = (long) intervalHours * 60L * 60L * 20L;
        periodicTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> check(null), intervalTicks, intervalTicks);
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

    // The click target is parsed as part of the tag, so it must be a plain
    // GitHub link: a tag name can contain "<", and an odd one gets the
    // generic releases page instead of a way into the message.
    private static String clickableLink(String url) {
        return url.matches("https://github\\.com/[A-Za-z0-9._/-]+") ? url : RELEASES_URL;
    }

    public Version currentVersion() {
        return currentVersion;
    }
}
