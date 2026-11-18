package com.jifercraft.bingo.game;

import com.cryptomorin.xseries.XEnchantment;
import com.cryptomorin.xseries.XMaterial;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Item building that works the same from 1.8 to 1.21. */
public final class LegacyItems {

    /**
     * Ends the name of the card item. Old versions have no persistent data on
     * items, and a run of color codes with nothing between them is invisible.
     */
    static final String CARD_MARKER = "§b§1§n§9§0§r";
    static final String LOBBY_VOTE_MARKER = "§b§1§n§9§3§r";
    static final String LOBBY_TEAMS_MARKER = "§b§1§n§9§1§r";
    static final String LOBBY_LEAVE_MARKER = "§b§1§n§9§2§r";

    // Dye colors with their RGB, to pick the pane or wool closest to a team color.
    private static final String[] DYES = {
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
    };
    private static final int[][] DYE_RGB = {
            {249, 255, 254}, {249, 128, 29}, {199, 78, 189}, {58, 179, 218}, {254, 216, 61}, {128, 199, 31},
            {243, 140, 170}, {71, 79, 82}, {156, 157, 151}, {22, 156, 156}, {137, 50, 184}, {60, 68, 170},
            {131, 84, 50}, {94, 124, 22}, {176, 46, 38}, {29, 29, 33},
    };

    private LegacyItems() {
    }

    public static ItemStack item(XMaterial material, String name, List<String> lore, boolean glow) {
        ItemStack stack = material.parseItem();
        if (stack == null) {
            stack = XMaterial.STONE.parseItem();
        }
        return decorate(stack, name, lore, glow);
    }

    /** Name, lore and glint on a stack; a null name keeps the client's own translated name. */
    public static ItemStack decorate(ItemStack stack, String name, List<String> lore, boolean glow) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.setDisplayName(name);
        }
        if (lore != null) {
            meta.setLore(lore);
        }
        meta.addItemFlags(ItemFlag.values());
        if (glow) {
            Enchantment unbreaking = XEnchantment.UNBREAKING.get();
            if (unbreaking != null) {
                meta.addEnchant(unbreaking, 1, true);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    static XMaterial paneFor(TextColor color) {
        return dyed(color, "_STAINED_GLASS_PANE", XMaterial.RED_STAINED_GLASS_PANE);
    }

    static XMaterial woolFor(TextColor color) {
        return dyed(color, "_WOOL", XMaterial.WHITE_WOOL);
    }

    private static XMaterial dyed(TextColor color, String suffix, XMaterial fallback) {
        if (color == null) {
            return fallback;
        }
        if (NamedTextColor.RED.equals(color) || NamedTextColor.DARK_RED.equals(color) || isReddish(color)) {
            return XMaterial.matchXMaterial("RED" + suffix).orElse(fallback);
        }
        if (NamedTextColor.GOLD.equals(color)) {
            return XMaterial.matchXMaterial("ORANGE" + suffix).orElse(fallback);
        }
        if (NamedTextColor.YELLOW.equals(color)) {
            return XMaterial.matchXMaterial("YELLOW" + suffix).orElse(fallback);
        }
        if (NamedTextColor.GREEN.equals(color)) {
            return XMaterial.matchXMaterial("LIME" + suffix).orElse(fallback);
        }
        if (NamedTextColor.DARK_GREEN.equals(color)) {
            return XMaterial.matchXMaterial("GREEN" + suffix).orElse(fallback);
        }
        if (NamedTextColor.AQUA.equals(color)) {
            return XMaterial.matchXMaterial("LIGHT_BLUE" + suffix).orElse(fallback);
        }
        if (NamedTextColor.DARK_AQUA.equals(color)) {
            return XMaterial.matchXMaterial("CYAN" + suffix).orElse(fallback);
        }
        if (NamedTextColor.BLUE.equals(color) || NamedTextColor.DARK_BLUE.equals(color)) {
            return XMaterial.matchXMaterial("BLUE" + suffix).orElse(fallback);
        }
        if (NamedTextColor.LIGHT_PURPLE.equals(color)) {
            return XMaterial.matchXMaterial("PINK" + suffix).orElse(fallback);
        }
        if (NamedTextColor.DARK_PURPLE.equals(color)) {
            return XMaterial.matchXMaterial("PURPLE" + suffix).orElse(fallback);
        }
        if (NamedTextColor.GRAY.equals(color)) {
            return XMaterial.matchXMaterial("LIGHT_GRAY" + suffix).orElse(fallback);
        }
        if (NamedTextColor.DARK_GRAY.equals(color)) {
            return XMaterial.matchXMaterial("GRAY" + suffix).orElse(fallback);
        }
        if (NamedTextColor.BLACK.equals(color)) {
            return XMaterial.matchXMaterial("BLACK" + suffix).orElse(fallback);
        }
        if (NamedTextColor.WHITE.equals(color)) {
            return XMaterial.matchXMaterial("WHITE" + suffix).orElse(fallback);
        }
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < DYES.length; i++) {
            long dr = color.red() - DYE_RGB[i][0];
            long dg = color.green() - DYE_RGB[i][1];
            long db = color.blue() - DYE_RGB[i][2];
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return XMaterial.matchXMaterial(DYES[best] + suffix).orElse(fallback);
    }

    private static boolean isReddish(TextColor color) {
        int r = color.red();
        int g = color.green();
        int b = color.blue();
        return r > 160 && g < 110 && b < 110;
    }

    /** "minecraft:story/mine_stone" as "Mine Stone", for places that only take plain text. */
    static String prettyKey(String key) {
        String path = key.substring(key.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }
}
