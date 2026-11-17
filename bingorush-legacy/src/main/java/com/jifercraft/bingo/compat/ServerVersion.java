package com.jifercraft.bingo.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * The Minecraft version the server runs, read once from Bukkit's version
 * string. Features that need a newer server ask here instead of catching
 * NoSuchMethodError all over the place.
 */
public final class ServerVersion {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int major = 1;
    private static int minor = 8;
    private static int patch;

    private ServerVersion() {
    }

    public static void detect() {
        Matcher matcher = VERSION.matcher(Bukkit.getBukkitVersion());
        if (matcher.find()) {
            major = Integer.parseInt(matcher.group(1));
            minor = Integer.parseInt(matcher.group(2));
            patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        }
    }

    /** True on 1.minor or newer; year-based versions (26.x) count as newer than any 1.x. */
    public static boolean atLeast(int minorVersion) {
        return major > 1 || minor >= minorVersion;
    }

    /** True on 1.minor.patch or newer. */
    public static boolean atLeast(int minorVersion, int patchVersion) {
        return major > 1 || minor > minorVersion || minor == minorVersion && patch >= patchVersion;
    }

    /** Servers past 1.21 should run the Modern jar. */
    public static boolean isPastLegacyRange() {
        return major > 1;
    }

    public static String describe() {
        return major + "." + minor + (patch > 0 ? "." + patch : "");
    }
}
