package com.jifercraft.bingo.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Preserves comments, headers and indentation from the bundled jar template,
 * while maintaining existing values and double-quoted string formatting.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static void update(JavaPlugin plugin, String resourceName) {
        File folderFile = new File(plugin.getDataFolder(), resourceName);
        if (!folderFile.exists()) {
            plugin.saveResource(resourceName, false);
            return;
        }

        InputStream jarStream = plugin.getResource(resourceName);
        if (jarStream == null) {
            return;
        }

        List<String> jarLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(jarStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                jarLines.add(line);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled " + resourceName + ": " + e.getMessage());
            return;
        }

        YamlConfiguration folderConfig = YamlConfiguration.loadConfiguration(folderFile);
        InputStream jarStreamForYaml = plugin.getResource(resourceName);
        if (jarStreamForYaml == null) {
            return;
        }
        YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarStreamForYaml, StandardCharsets.UTF_8));

        if (!mergeMissing(jarConfig, folderConfig)) {
            return;
        }

        String updatedContent = buildUpdatedContent(jarLines, folderConfig);
        try (BufferedWriter writer = Files.newBufferedWriter(folderFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(updatedContent);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write updated " + resourceName + ": " + e.getMessage());
        }
    }

    /** Copies keys the template has and the server file lacks. True when anything was added. */
    static boolean mergeMissing(YamlConfiguration jarConfig, YamlConfiguration folderConfig) {
        boolean hasChanges = false;
        for (String key : jarConfig.getKeys(true)) {
            if (folderConfig.get(key) != null) {
                continue;
            }
            folderConfig.set(key, jarConfig.get(key));
            hasChanges = true;
        }
        return hasChanges;
    }

    static String buildUpdatedContent(List<String> jarLines, YamlConfiguration folderConfig) {
        Map<Integer, FileLine> fileMap = new LinkedHashMap<>();
        int positions = 0;

        // Indent of the list header we are inside, or -1. The list is rewritten
        // from the server's values, so every template line under it is skipped,
        // not only the "- " ones. Otherwise the second key of a map item (the
        // color under "- name: Rojo") leaks out as a stray "color: null".
        int listIndent = -1;
        for (int i = 0; i < jarLines.size(); i++) {
            String line = jarLines.get(i);
            if (listIndent >= 0) {
                if (line.isBlank() || indentOf(line) > listIndent || isListContent(line)) {
                    if (line.isBlank()) {
                        fileMap.put(positions++, new FileLine(line, false, false, false));
                    }
                    continue;
                }
                listIndent = -1;
            }
            if (isListContent(line)) {
                continue;
            }

            if (!line.isEmpty() && !isComment(line) && line.contains(":")) {
                String[] split = line.split(":", 2);
                boolean isValue = split.length > 1 && !split[1].trim().isEmpty() && !isComment(split[1].trim());
                boolean isHeader = split.length == 1 || split[1].trim().isEmpty() || isComment(split[1].trim());
                boolean isList = isHeader && i + 1 < jarLines.size() && isListContent(jarLines.get(i + 1));
                fileMap.put(positions++, new FileLine(line, isValue, isHeader, isList));
                if (isList) {
                    listIndent = indentOf(line);
                }
                continue;
            }

            fileMap.put(positions++, new FileLine(line, false, false, false));
        }

        StringBuilder builder = new StringBuilder();
        Map<Integer, String> headers = new HashMap<>();

        for (int pos = 0; pos < positions; pos++) {
            FileLine fileLine = fileMap.get(pos);
            String line = fileLine.line();

            if (!fileLine.isValue() && !fileLine.isHeader()) {
                builder.append(line).append("\n");
                continue;
            }

            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') {
                spaces++;
            }
            int point = spaces / 2;
            String identifier = line.substring(spaces).split(":", 2)[0].trim();
            if (fileLine.isHeader()) {
                headers.put(point, identifier);
            }

            if (fileLine.isValue()) {
                StringBuilder path = new StringBuilder();
                for (int i = 0; i < point; i++) {
                    String header = headers.get(i);
                    if (header != null) {
                        path.append(header).append(".");
                    }
                }
                path.append(identifier);

                builder.append(" ".repeat(spaces)).append(identifier).append(": ");
                Object value = folderConfig.get(path.toString());
                if (value instanceof String str) {
                    builder.append(quote(str)).append("\n");
                } else {
                    builder.append(value).append("\n");
                }
                continue;
            }

            if (fileLine.isList()) {
                StringBuilder path = new StringBuilder();
                for (int i = 0; i < point; i++) {
                    String header = headers.get(i);
                    if (header != null) {
                        path.append(header).append(".");
                    }
                }
                path.append(identifier);

                builder.append(" ".repeat(spaces)).append(identifier).append(":");
                List<?> valueList = folderConfig.getList(path.toString());
                if (valueList == null || valueList.isEmpty()) {
                    builder.append(" []\n");
                } else {
                    builder.append("\n");
                    for (Object item : valueList) {
                        if (item instanceof Map<?, ?> map) {
                            boolean first = true;
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                if (first) {
                                    builder.append(" ".repeat(spaces + 2)).append("- ").append(entry.getKey()).append(": ");
                                    first = false;
                                } else {
                                    builder.append(" ".repeat(spaces + 4)).append(entry.getKey()).append(": ");
                                }
                                Object val = entry.getValue();
                                if (val instanceof String s) {
                                    builder.append(quote(s)).append("\n");
                                } else {
                                    builder.append(val).append("\n");
                                }
                            }
                        } else if (item instanceof String s) {
                            builder.append(" ".repeat(spaces + 2)).append("- ").append(quote(s)).append("\n");
                        } else {
                            builder.append(" ".repeat(spaces + 2)).append("- ").append(item).append("\n");
                        }
                    }
                }
                continue;
            }

            builder.append(line).append("\n");
        }

        // Blank lines at the end of the template would otherwise be copied
        // into every rewritten file; one final newline is enough.
        return builder.toString().stripTrailing() + "\n";
    }

    /**
     * A YAML double-quoted scalar. Line breaks and backslashes are escaped:
     * a raw line break inside the quotes folds into a space on the next load,
     * which would flatten messages such as the history pagination.
     */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean isComment(String s) {
        return s.trim().startsWith("#");
    }

    private static int indentOf(String s) {
        int spaces = 0;
        while (spaces < s.length() && s.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private static boolean isListContent(String s) {
        return s.trim().startsWith("-");
    }

    private record FileLine(String line, boolean isValue, boolean isHeader, boolean isList) {
    }
}
