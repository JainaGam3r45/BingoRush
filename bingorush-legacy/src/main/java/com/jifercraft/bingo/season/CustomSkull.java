package com.jifercraft.bingo.season;

import com.cryptomorin.xseries.XBlock;
import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.profiles.builder.XSkull;
import com.cryptomorin.xseries.profiles.objects.Profileable;
import org.bukkit.block.Block;

/**
 * Utility to apply custom textures to player head blocks on Spigot 1.8.8+.
 */
public final class CustomSkull {

    private CustomSkull() {
    }

    public static void setSkull(Block block, String texture) {
        if (block == null) {
            return;
        }
        XBlock.setType(block, XMaterial.PLAYER_HEAD);
        if (texture == null || texture.isEmpty()) {
            return;
        }
        try {
            XSkull.of(block).profile(Profileable.detect(texture)).apply();
        } catch (Throwable ignored) {
        }
    }
}
