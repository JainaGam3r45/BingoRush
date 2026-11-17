package com.jifercraft.bingo.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The language file picked in config.yml, parsed. Files live in
 * lang/messages_<code>.yml; English and Spanish ship with the plugin and an
 * admin can drop in more. The palette at the top of the file becomes
 * MiniMessage tags, so <hl> and friends work in every template without being
 * hardcoded here. The plugin name, its gradient and the prefix are defined
 * once in the file and exposed as tags too.
 */
public final class Messages {

    private static final Pattern LINE_BREAK = Pattern.compile("<br>|<newline>");
    /** Languages inside the jar, kept up to date with new keys on every start. */
    private static final List<String> BUNDLED = List.of("en", "es");
    private static final String FALLBACK = "en";
    // The code ends up in a file name, so nothing that could leave lang/.
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z0-9_-]+");

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, TextColor> colors = new LinkedHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private YamlConfiguration yaml;
    private String fileName = "lang/messages_" + FALLBACK + ".yml";
    private TagResolver base = TagResolver.empty();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
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
                TextColor color = TextColor.fromHexString(palette.getString(name, ""));
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

    public Component render(String key, TagResolver... placeholders) {
        // Single-argument getString reads the English defaults for missing keys.
        String raw = yaml.getString(key);
        if (raw == null) {
            if (warned.add(key)) {
                plugin.getLogger().warning(fileName + " has no key '" + key + "'");
            }
            raw = key;
        }
        TagResolver resolver = placeholders.length == 0 ? base : TagResolver.resolver(base, TagResolver.resolver(placeholders));
        return miniMessage.deserialize(raw, resolver);
    }

    /**
     * One template as item lore. Lore cannot show a line break inside a line,
     * so the template is cut at each {@code <br>} and every piece becomes its
     * own line, without the italics the client adds to lore by default.
     */
    public List<Component> renderLore(String key, TagResolver... placeholders) {
        String raw = yaml.getString(key);
        if (raw == null) {
            return List.of(render(key, placeholders));
        }
        TagResolver resolver = placeholders.length == 0 ? base : TagResolver.resolver(base, TagResolver.resolver(placeholders));
        List<Component> lines = new ArrayList<>();
        for (String piece : LINE_BREAK.split(raw, -1)) {
            lines.add(miniMessage.deserialize("<!italic>" + piece, resolver));
        }
        return lines;
    }

    /**
     * Sends one template. The console gets the colored component too: Paper
     * turns it into ANSI codes for the terminal and strips them from the log
     * file.
     */
    public void send(CommandSender to, String key, TagResolver... placeholders) {
        to.sendMessage(render(key, placeholders));
    }

    /**
     * An informational console line with the plugin prefix, for round events
     * and startup. The text is for admins reading the console, so it stays in
     * Java and in English; values go in as placeholders, never concatenated,
     * so a team name cannot inject tags. Warnings and errors keep using the
     * logger, which tags them with their level.
     */
    public void console(String template, TagResolver... placeholders) {
        TagResolver resolver = placeholders.length == 0 ? base : TagResolver.resolver(base, TagResolver.resolver(placeholders));
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize("<prefix><body>" + template, resolver));
    }

    /** True when the template of a key contains some text, to check what an admin wrote. */
    public boolean templateHas(String key, String text) {
        String raw = yaml.getString(key);
        return raw != null && raw.contains(text);
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
}
