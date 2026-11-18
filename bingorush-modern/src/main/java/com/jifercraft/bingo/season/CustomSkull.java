package com.jifercraft.bingo.season;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;

/**
 * Utility to apply custom textures to player head blocks on Paper.
 */
public final class CustomSkull {

    private CustomSkull() {
    }

    @SuppressWarnings("deprecation")
    public static void setSkull(Block block, String texture) {
        if (block == null) {
            return;
        }
        block.setType(Material.PLAYER_HEAD, false);
        if (texture == null || texture.isEmpty()) {
            return;
        }
        if (block.getState() instanceof Skull skull) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "");
                profile.setProperty(new ProfileProperty("textures", texture));
                skull.setOwnerProfile(profile);
                skull.update(true, false);
            } catch (Throwable ignored) {
            }
        }
    }
}
