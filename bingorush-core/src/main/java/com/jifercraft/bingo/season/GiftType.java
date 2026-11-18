package com.jifercraft.bingo.season;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One kind of gift: its name, head texture, particle, sounds and reward commands. */
public final class GiftType {

    private final String id;
    private final String displayName;
    private final String texture;
    private final String particle;
    private final String proximitySound;
    private final String clickSound;
    private final String clickParticle;
    private final List<String> rewardCommands;

    public GiftType(String id, String displayName, String texture, String particle,
            String proximitySound, String clickSound, String clickParticle,
            List<String> rewardCommands) {
        this.id = id != null ? id : "gift";
        this.displayName = displayName != null ? displayName : "Gift";
        this.texture = texture != null ? texture : "";
        this.particle = particle != null ? particle : "VILLAGER_HAPPY";
        this.proximitySound = proximitySound != null ? proximitySound : "block.note_block.chime";
        this.clickSound = clickSound != null ? clickSound : "entity.player.levelup";
        this.clickParticle = clickParticle != null ? clickParticle : "FIREWORK";
        this.rewardCommands = rewardCommands != null
                ? Collections.unmodifiableList(new ArrayList<>(rewardCommands))
                : Collections.emptyList();
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String texture() {
        return texture;
    }

    public String particle() {
        return particle;
    }

    public String proximitySound() {
        return proximitySound;
    }

    public String clickSound() {
        return clickSound;
    }

    public String clickParticle() {
        return clickParticle;
    }

    public List<String> rewardCommands() {
        return rewardCommands;
    }
}
