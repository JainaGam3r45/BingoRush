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

        boolean hasChanges = false;
        for (String key : jarConfig.getKeys(true)) {
            if (folderConfig.get(key) != null) {
                continue;
            }
            folderConfig.set(key, jarConfig.get(key));
            hasChanges = true;
        }

        if (!hasChanges) {
            return;
        }

        String updatedContent = buildUpdatedContent(jarLines, folderConfig);
        try (BufferedWriter writer = Files.newBufferedWriter(folderFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(updatedContent);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write updated " + resourceName + ": " + e.getMessage());
        }
    }

    static String buildUpdatedContent(List<String> jarLines, YamlConfiguration folderConfig) {
        Map<Integer, FileLine> fileMap = new LinkedHashMap<>();
        int positions = 0;

        for (int i = 0; i < jarLines.size(); i++) {
            String line = jarLines.get(i);
            if (isListContent(line)) {
                continue;
            }

            if (!line.isEmpty() && !isComment(line) && line.contains(":")) {
                String[] split = line.split(":", 2);
                boolean isValue = split.length > 1 && !split[1].trim().isEmpty() && !isComment(split[1].trim());
                boolean isHeader = split.length == 1 || split[1].trim().isEmpty() || isComment(split[1].trim());
                boolean isList = isHeader && i + 1 < jarLines.size() && isListContent(jarLines.get(i + 1));
                fileMap.put(positions++, new FileLine(line, isValue, isHeader, isList));
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
                    builder.append("\"").append(str.replace("\"", "\\\"")).append("\"\n");
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
                                    builder.append("\"").append(s.replace("\"", "\\\"")).append("\"\n");
                                } else {
                                    builder.append(val).append("\n");
                                }
                            }
                        } else if (item instanceof String s) {
                            builder.append(" ".repeat(spaces + 2)).append("- \"").append(s.replace("\"", "\\\"")).append("\"\n");
                        } else {
                            builder.append(" ".repeat(spaces + 2)).append("- ").append(item).append("\n");
                        }
                    }
                }
                continue;
            }

            builder.append(line).append("\n");
        }

        return builder.toString();
    }

    private static boolean isComment(String s) {
        return s.trim().startsWith("#");
    }

    private static boolean isListContent(String s) {
        return s.trim().startsWith("-");
    }

    private record FileLine(String line, boolean isValue, boolean isHeader, boolean isList) {
    }
}
