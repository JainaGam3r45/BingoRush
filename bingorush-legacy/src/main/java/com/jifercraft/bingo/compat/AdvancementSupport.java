package com.jifercraft.bingo.compat;

import com.jifercraft.bingo.BingoRush;
import com.jifercraft.bingo.config.BingoConfig;
import com.jifercraft.bingo.game.Game;
import com.jifercraft.bingo.game.Round;
import com.jifercraft.bingo.game.Team;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Everything that needs advancements, which arrived in 1.12: the card tab in
 * the advancements screen, setting owned card advancements aside during a
 * round, and the advancement event itself. The real code is compiled against
 * the 1.12 API and only loaded on a server that has it; older servers get
 * the empty version below.
 */
public interface AdvancementSupport {

    String IMPLEMENTATION = "com.jifercraft.bingo.compat.v1_12.Advancements";

    boolean loadTab(Round round, BingoConfig config);

    boolean tabLoaded();

    void syncTab(Player player, Round round, Team team);

    void clearTab(Player player);

    void retireTab();

    void revokeOwned(Player player, Round round, Team team);

    Map<String, List<String>> takeOwned(UUID playerId);

    Set<UUID> ownedPlayers();

    Map<String, Map<String, List<String>>> ownedSnapshot();

    void loadOwned(Map<UUID, Map<String, List<String>>> saved);

    void award(Player player, Map<String, ? extends Collection<String>> owed);

    /** Turns vanilla advancement chat lines off for the round; Spigot has no way to edit them. */
    void silenceAnnouncements(boolean silence);

    static AdvancementSupport load(BingoRush plugin, Game game) {
        if (!ServerVersion.atLeast(12)) {
            return NONE;
        }
        try {
            // Our own class, picked by name so the 1.8 code never links against it.
            return (AdvancementSupport) Class.forName(IMPLEMENTATION)
                    .getConstructor(BingoRush.class, Game.class)
                    .newInstance(plugin, game);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Advancement features are off, they could not be loaded: " + e);
            return NONE;
        }
    }

    AdvancementSupport NONE = new AdvancementSupport() {
        @Override
        public boolean loadTab(Round round, BingoConfig config) {
            return false;
        }

        @Override
        public boolean tabLoaded() {
            return false;
        }

        @Override
        public void syncTab(Player player, Round round, Team team) {
        }

        @Override
        public void clearTab(Player player) {
        }

        @Override
        public void retireTab() {
        }

        @Override
        public void revokeOwned(Player player, Round round, Team team) {
        }

        @Override
        public Map<String, List<String>> takeOwned(UUID playerId) {
            return Collections.emptyMap();
        }

        @Override
        public Set<UUID> ownedPlayers() {
            return Collections.emptySet();
        }

        @Override
        public Map<String, Map<String, List<String>>> ownedSnapshot() {
            return Collections.emptyMap();
        }

        @Override
        public void loadOwned(Map<UUID, Map<String, List<String>>> saved) {
        }

        @Override
        public void award(Player player, Map<String, ? extends Collection<String>> owed) {
        }

        @Override
        public void silenceAnnouncements(boolean silence) {
        }
    };
}
