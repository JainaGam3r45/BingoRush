package com.jifercraft.bingo.season;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;

/** Places a player head with a texture from the config, for the gifts. */
final class CustomSkull {

    private CustomSkull() {
    }

    static void place(Block block, String texture) {
        block.setType(Material.PLAYER_HEAD, false);
        if (texture == null || texture.isEmpty() || !(block.getState(false) instanceof Skull skull)) {
            return;
        }
        // A static profile with only the texture: nothing to look up online.
        skull.setProfile(ResolvableProfile.resolvableProfile()
                .addProperty(new ProfileProperty("textures", texture))
                .build());
    }
}
