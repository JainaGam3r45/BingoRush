# BingoRush

Bingo minigame for Minecraft event servers, from 1.8.8 to the latest Paper.

Teams compete on a shared 5x5 card. The first team to complete a line or the whole card wins the round.

## How it works

- Two game modes: collect items, or complete vanilla advancements during the round.
- Cards come from a pool split into difficulty tiers (easy, medium, hard, epic). Seeds can be numbers or plain text, such as an event name, so a board can be replayed or shared between servers.
- Win by completing a line (row, column or diagonal) or the full card.
- Lockout makes every cell exclusive: the first team to claim it keeps it, and on a full card 13 cells win.
- Lives turn deaths into knockouts. A player who runs out watches as a spectator, and the last team standing wins. One life is hardcore.
- When time runs out, the team with the most cells wins, and the earlier last claim breaks a tie.
- The card also shows up as its own tab in the advancements screen, with your team's cells marked as done.
- Optionally, players on a team show in their team color in the tab list and in chat; the Modern jar also groups the tab by team. Both are off by default (`tab.enabled`, `chat.enabled`) because another tab or chat plugin may own them; the texts are in the language files.
- A player who joins after the start can be put in a team or made a spectator (`late-join`), and `/bingo spectate` lets anyone without a team watch.
- An unfinished round is saved to disk and comes back paused after a crash or restart.
- Finished matches are logged to `data/matches/` and can be browsed in game.

## Which jar

BingoRush ships two jars. Pick the one that matches your server:

| Jar | Java | Server |
| :-- | :-- | :-- |
| `BingoRush-Legacy-<version>.jar` | 8+ | Spigot or Paper 1.8.8 to 1.21.x |
| `BingoRush-Modern-<version>.jar` | 25+ | Paper 26.x and newer |

Both read the same config and language files. On the Legacy jar, features the server version does not have turn themselves off and say so in the console: advancement rounds, the advancements tab and owned advancements need 1.12, and items or advancements newer than the server are left out of the pools. The advancements tab starts off on the Legacy jar, because older servers reload every player's advancements once per tab entry.

## Building

```bash
./gradlew build
```

Both jars end up in `build/libs/`. The project has three modules: `bingorush-core` with the game rules and language files (Java 8, no server API), `bingorush-modern` and `bingorush-legacy`.

## Commands and permissions

Player commands (`bingo.play`):

- `/bingo` or `/b`: help.
- `/bingo join [team]`: joins a team, or lets you pick one when several have room.
- `/bingo teams` or `/teams`: team selector menu.
- `/bingo card` or `/card`: opens your team's card.
- `/bingo coords` or `/bingo c`: sends your coordinates to your team.
- `/bingo leave`: leaves your team.
- `/bingo spectate`: watches the running round from spectator mode, if you are not on a team.
- `/bingo version`: shows the installed version; admins also get an update check.

Admin commands (`bingo.admin`):

- `/bingo setup` or `/setup`: panel to pick mode, win condition, lockout, lives and time limit, then start.
- `/bingo start [items|advancements] [lockout:true|false] [seed]`: starts a round.
- `/bingo stop`, `/bingo pause`, `/bingo resume`: control the running round.
- `/bingo reroll`: prepares a new card for the next round.
- `/bingo card <team>`: watches a team's card, for casters.
- `/bingo teams <add|remove|join|clear>`: manages teams and assignments.
- `/bingo history [page|id]`, `/bingo history clear`, `/bingo export <id>`: match history.
- `/bingo reload`: rereads `config.yml` and the language files.

`bingo.update` (default: op) receives update notices on join.

## Configuration

Files live in `plugins/BingoRush/`. New keys are added to existing files on startup and your values are kept.

- `config.yml`: game mode, win mode, lockout, lives, time limit, teams, item and advancement pools, sounds, round chat, the advancements tab, update checks and history.
- `lang/messages_<code>.yml`: everything players read, in MiniMessage, with the color palette at the top. English (`en`, the default) and Spanish (`es`) ship with the plugin; pick one with `language` in `config.yml`, or copy `messages_en.yml` to add your own. Missing keys fall back to English.

### Settings reference

| Key | Default | What it does | Legacy vs Modern differences |
| :-- | :-- | :-- | :-- |
| `language` | `en` | Language file to load from `lang/messages_<code>.yml`. | None. |
| `game-mode` | `ITEMS` | Default round mode: `ITEMS` or `ADVANCEMENTS`. | Advancements need 1.12+ on Legacy. |
| `win-mode` | `LINE` | Win condition: `LINE` (row, column, diagonal) or `FULL_CARD`. | None. |
| `lockout` | `false` | When true, each cell is claimed by only one team. | None. |
| `time-limit-seconds` | `1800` | Round time limit in seconds. 0 disables the timer. | None. |
| `countdown-seconds` | `10` | Seconds for the start countdown. 0 starts immediately. | None. |
| `clear-inventories` | `true` | Empties inventories during the round and restores them after. | None. |
| `scatter-radius` | `100` | Block radius around world spawn to scatter teams at round start. | None. |
| `late-join` | `JOIN` | What to do with players connecting mid-round: `JOIN` or `SPECTATE`. | None. |
| `lives` | `0` | Lives per player before knockout. 0 means unlimited, 1 is hardcore. | None. |
| `round-chat.deaths` | `custom` | Death message format: `custom`, `vanilla`, or `hidden`. | None. |
| `round-chat.advancements` | `custom` (Modern) / `vanilla` (Legacy) | Advancement message format: `custom`, `vanilla`, or `hidden`. | Defaults to `vanilla` on Legacy (see limits below). |
| `advancement-tab` | `true` (Modern) / `false` (Legacy) | Shows the round card as a tab in the advancements screen (L). | Starts off on Legacy due to reload cost on 1.12+. |
| `tab.enabled` | `false` | Colors player names by team in the player list. | Modern also groups tab by team. |
| `tab.header` | `true` | Custom header for the player list. | None. |
| `tab.footer` | `true` | Custom footer for the player list. | None. |
| `chat.enabled` | `false` | Formats in-game chat with team colors and prefixes. | None. |
| `update-notifier.enabled` | `true` | Checks GitHub releases for newer versions. | None. |
| `update-notifier.on-join` | `true` | Alerts admins with permission on server join. | None. |
| `update-notifier.check-interval-hours` | `24` | Hours between automatic background checks. 0 disables. | None. |
| `history.enabled` | `true` | Saves match summaries to `data/matches/` as JSON. | None. |
| `history.max-saved-matches` | `50` | Maximum match records to retain on disk. | None. |

## Known limits on Legacy

- **Scoreboard length**: on Minecraft versions before 1.18, scoreboard lines cannot exceed 40 characters. Longer team names or values are clamped to avoid client crashes.
- **Player list grouping**: Spigot before Paper 26.x lacks native tab list order control. Team members are colored in the tab, but not grouped together.
- **Advancement chat rule**: Spigot lacks per-player advancement message filtering. Setting `round-chat.advancements` to anything other than `vanilla` toggles the global `announceAdvancements` gamerule for the whole server while the round is running.

## Notes and verification status

- The sorting direction in Modern's `setPlayerListOrder` has not been visually verified with multiple client versions.

## Third-party notices

The Legacy jar bundles Adventure, Adventure Platform, Examination and Option (by KyoriPowered) and XSeries (by Crypto Morin), all under the MIT License and moved to the package `com.jifercraft.bingo.lib`. Their license texts are inside the jar under `META-INF/licenses/`, listed in `META-INF/THIRD-PARTY-NOTICES.txt`. The Modern jar bundles nothing from outside.

## License and credits

Distributed under the MIT License. See [LICENSE](LICENSE) for details.

Developed and maintained by [JainaGam3r45](https://github.com/JainaGam3r45). Infrastructure provided by [JiferCloud Hosting](https://www.jifercraft.com).
