package com.jifercraft.bingo.season;

import com.cryptomorin.xseries.XBlock;
import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.profiles.builder.XSkull;
import com.cryptomorin.xseries.profiles.objects.Profileable;
import java.util.logging.Logger;
import org.bukkit.block.Block;

/** Places a player head with a texture from the config, for the gifts. */
final class CustomSkull {

    private static boolean warned;

    private CustomSkull() {
    }

    static void setSkull(Block block, String texture, Logger logger) {
        XBlock.setType(block, XMaterial.PLAYER_HEAD);
        if (texture == null || texture.isEmpty()) {
            return;
        }
        try {
            XSkull.of(block).profile(Profileable.detect(texture)).apply();
        } catch (RuntimeException e) {
            // A texture the server will not take leaves a plain head; the
            // gift still works. Once is enough to know, not once per gift.
            if (!warned) {
                warned = true;
                logger.warning("Gift heads stay plain on this server, the texture could not be applied: "
                        + e.getMessage());
            }
        }
    }
}
