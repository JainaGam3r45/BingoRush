package com.jifercraft.bingo.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * messages.yml, parsed. The palette at the top of the file becomes MiniMessage
 * tags, so <hl> and friends work in every template without being hardcoded
 * here. The plugin name, its gradient and the prefix are defined once in the
 * file and exposed as tags too.
 */
public final class Messages {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, TextColor> colors = new LinkedHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private YamlConfiguration yaml;
    private TagResolver base = TagResolver.empty();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        // New keys from the bundled file land in the admin's copy instead of
        // rendering as raw key names after an update.
        InputStreamReader bundled = new InputStreamReader(plugin.getResource("messages.yml"), StandardCharsets.UTF_8);
        yaml.setDefaults(YamlConfiguration.loadConfiguration(bundled));
        yaml.options().copyDefaults(true);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not update messages.yml: " + e.getMessage());
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

    public Component render(String key, TagResolver... placeholders) {
        String raw = yaml.getString(key, null);
        if (raw == null) {
            if (warned.add(key)) {
                plugin.getLogger().warning("messages.yml has no key '" + key + "'");
            }
            raw = key;
        }
        TagResolver resolver = placeholders.length == 0 ? base : TagResolver.resolver(base, TagResolver.resolver(placeholders));
        return miniMessage.deserialize(raw, resolver);
    }

    /**
     * Sends one template. Players get the rendered component; the console gets
     * the same line as plain text, without colors.
     */
    public void send(CommandSender to, String key, TagResolver... placeholders) {
        Component rendered = render(key, placeholders);
        if (to instanceof Player) {
            to.sendMessage(rendered);
        } else {
            to.sendMessage(PlainTextComponentSerializer.plainText().serialize(rendered));
        }
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
