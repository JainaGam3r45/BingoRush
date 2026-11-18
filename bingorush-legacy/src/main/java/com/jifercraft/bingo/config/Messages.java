package com.jifercraft.bingo.config;

import com.jifercraft.bingo.compat.ServerVersion;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The language file picked in config.yml, parsed. The same lang files as the
 * Modern jar, in MiniMessage; chat, titles and boss bars go through
 * Adventure's Bukkit platform, which speaks every protocol from 1.8 on.
 * Item names, inventory titles and scoreboard lines only take legacy text on
 * these versions, so they get the same component flattened to § codes.
 */
public final class Messages {

    private static final Pattern LINE_BREAK = Pattern.compile("<br>|<newline>");
    /** Languages inside the jar, kept up to date with new keys on every start. */
    private static final List<String> BUNDLED = Arrays.asList("en", "es");
    private static final String FALLBACK = "en";
    // The code ends up in a file name, so nothing that could leave lang/.
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z0-9_-]+");

    private final JavaPlugin plugin;
    private final BukkitAudiences audiences;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    // Hex colors exist from 1.16 on; older clients get the nearest of the 16.
    private final LegacyComponentSerializer legacy = ServerVersion.atLeast(16)
            ? LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
            : LegacyComponentSerializer.legacySection();
    private final Map<String, TextColor> colors = new LinkedHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private YamlConfiguration yaml;
    private String fileName = path(FALLBACK);
    private TagResolver base = TagResolver.empty();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.audiences = BukkitAudiences.create(plugin);
        reload();
    }

    public void reload() {
        for (String bundled : BUNDLED) {
            String resource = path(bundled);
            if (!new File(plugin.getDataFolder(), resource).exists()) {
                plugin.saveResource(resource, false);
            } else {
                ConfigUpdater.update(plugin, resource);
            }
        }
        String language = plugin.getConfig().getString("language", FALLBACK).trim().toLowerCase(Locale.ROOT);
        File file = new File(plugin.getDataFolder(), path(language));
        if (!LANGUAGE_CODE.matcher(language).matches() || !file.isFile()) {
            plugin.getLogger().warning("language is '" + language + "' but there is no " + path(language)
                    + ", using English");
            language = FALLBACK;
            file = new File(plugin.getDataFolder(), path(FALLBACK));
        }
        fileName = path(language);
        yaml = YamlConfiguration.loadConfiguration(file);
        // A translation an admin added may lack the keys of a newer version;
        // those read the English text from the jar instead of the key name.
        InputStream english = plugin.getResource(path(FALLBACK));
        if (english != null) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(english, StandardCharsets.UTF_8)));
        }

        colors.clear();
        warned.clear();
        TagResolver.Builder builder = TagResolver.builder();
        ConfigurationSection palette = yaml.getConfigurationSection("palette");
        if (palette != null) {
            for (String name : palette.getKeys(false)) {
                String hex = palette.getString(name, "");
                TextColor color = hex == null ? null : TextColor.fromHexString(hex);
                if (color == null) {
                    plugin.getLogger().warning("palette." + name + " is not a color like #RRGGBB");
                    continue;
                }
                colors.put(name, color);
                builder.tag(name, Tag.styling(color));
            }
        }
        Component brand = miniMessage.deserialize(yaml.getString("brand", "BingoRush"), builder.build());
        builder.tag("brand", Tag.selfClosingInserting(brand));
        Component prefix = miniMessage.deserialize(yaml.getString("prefix", "<brand> » "), builder.build());
        builder.tag("prefix", Tag.selfClosingInserting(prefix));
        base = builder.build();
    }

    private static String path(String language) {
        return "lang/messages_" + language + ".yml";
    }

    /**
     * Moves a messages.yml from before the lang folder to lang/messages_es.yml.
     * That file was Spanish and may be customized, so it is kept as the
     * Spanish translation. True when a file was moved.
     */
    public static boolean migrateOldMessages(JavaPlugin plugin) {
        File old = new File(plugin.getDataFolder(), "messages.yml");
        File spanish = new File(plugin.getDataFolder(), path("es"));
        if (!old.isFile() || spanish.exists()) {
            return false;
        }
        try {
            Files.createDirectories(spanish.getParentFile().toPath());
            Files.move(old.toPath(), spanish.toPath());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not move messages.yml to " + path("es") + ": " + e.getMessage());
            return false;
        }
    }

    private TagResolver resolver(TagResolver... placeholders) {
        return placeholders.length == 0 ? base : TagResolver.resolver(base, TagResolver.resolver(placeholders));
    }

    public Component render(String key, TagResolver... placeholders) {
        // Single-argument getString reads the English defaults for missing keys.
        String raw = yaml.getString(key);
        if (raw == null) {
            if (warned.add(key)) {
                plugin.getLogger().warning(fileName + " has no key '" + key + "'");
            }
            raw = key;
        }
        return miniMessage.deserialize(raw, resolver(placeholders));
    }

    /**
     * One template as item lore. Lore cannot show a line break inside a line,
     * so the template is cut at each {@code <br>} and every piece becomes its
     * own line, without the italics the client adds to lore by default.
     */
    public List<Component> renderLore(String key, TagResolver... placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) {
            return Collections.singletonList(render(key, placeholders));
        }
        List<Component> lines = new ArrayList<>();
        for (String piece : LINE_BREAK.split(raw, -1)) {
            lines.add(miniMessage.deserialize("<!italic>" + piece, resolver(placeholders)));
        }
        return lines;
    }

    /** Sends one template; the console gets it in color too. */
    public void send(CommandSender to, String key, TagResolver... placeholders) {
        audiences.sender(to).sendMessage(render(key, placeholders));
    }

    public void send(CommandSender to, Component message) {
        audiences.sender(to).sendMessage(message);
    }

    /** Titles, action bars, boss bars and sounds for one player. */
    public Audience audience(Player player) {
        return audiences.player(player);
    }

    /**
     * An informational console line with the plugin prefix, for round events
     * and startup. The text is for admins reading the console, so it stays in
     * Java and in English; values go in as placeholders, never concatenated,
     * so a team name cannot inject tags. Warnings and errors keep using the
     * logger, which tags them with their level.
     */
    public void console(String template, TagResolver... placeholders) {
        audiences.console().sendMessage(miniMessage.deserialize("<prefix><body>" + template, resolver(placeholders)));
    }

    /** A component as § text, for item names, inventory titles and scoreboards. */
    public String legacy(Component component) {
        return legacy.serialize(component);
    }

    /** § text in the 16 classic colors only, for places with a tight length limit. */
    public String legacyClassic(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public List<String> legacy(List<Component> components) {
        List<String> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(legacy(component));
        }
        return lines;
    }

    /**
     * A message rendered and flattened to plain text, for the places a plain
     * string is needed, such as a team name the plugin invents.
     */
    public String plainText(String key, TagResolver... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(render(key, placeholders));
    }

    /** A palette color for hand-built interface text, such as the sidebar. */
    public TextColor color(String name) {
        return colors.get(name);
    }

    public void close() {
        audiences.close();
    }
}
