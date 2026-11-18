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
- Every player shows their team in the player list, above their head and in chat: a short tag in the team color, such as **R** Pedro, with its own look for the lobby, the round and spectators (`team-display` in `config.yml`).
- Teams take a color by name (`red`, `dark_blue`) or hex and a tag; `/bingo teams auto 4` makes four ready-to-use teams named in the server's language.
- Players in a waiting room vote the round: mode, win condition, time, lockout, lives, PvP, time of day, or whatever else `match-voting.options` lists (Modern).
- Arenas: named sets of rules (mode, win condition, lockout, time, lives, team size, countdown, border) with their own waiting room. Create one with a command or from the `/bingo arena` menu, start from a preset, and change any setting later. With no arenas, rounds are played in the main world as before.
- On the Modern jar each arena plays in a world of its own, made on demand under `world/dimensions/bingorush/` with its own Nether and End, and deleted when it sits empty. Leftovers from a crash are cleaned on the next start.
- Waiting lobby with `/bingo setlobby` and `/bingo lobby`; players are automatically teleported to the lobby when a round finishes.
- Interactive signs (`[bingo]`) show arena state and player counts, letting players join with a right-click.
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

Player commands (`bingo.play`, everyone by default):

- `/bingo` or `/b`: help, showing only what you can use.
- `/bingo join [team]`: joins a team, or lets you pick one when several have room.
- `/bingo randomjoin` or `/bingo autojoin`: joins the team with the fewest members.
- `/bingo teams` or `/teams`: team selector menu.
- `/bingo card` or `/card`: opens your team's card.
- `/bingo coords` or `/bingo c`: sends your coordinates to your team.
- `/bingo lobby`: teleports to the waiting lobby.
- `/bingo leave`: leaves your team, or the waiting room you are in.
- `/bingo spectate`: watches the running round from spectator mode, if you are not on a team.
- `/bingo vote`: votes the round settings from a waiting room (Modern only).
- `/bingo version`: shows the installed version; `bingo.update` also gets an update check.

Admin commands. `bingo.admin` (op by default) grants all of them; each group also has its own node:

| Node | Commands |
| :-- | :-- |
| `bingo.admin.round` | `/bingo start [items\|advancements] [lockout:true\|false] [seed]`, `/bingo stop`, `/bingo pause`, `/bingo resume`, `/bingo reroll`, `/bingo card <team>` (watch a team's card) |
| `bingo.admin.arena` | `/bingo arena ...` (below), `/bingo setlobby`, `/bingo structure ...` (Modern), placing and breaking `[bingo]` signs |
| `bingo.admin.teams` | `/bingo teams add <name> [color] [tag]`, `/bingo teams auto <count>`, `/bingo teams color <team> <color>`, `/bingo teams tag <team> <tag>`, `/bingo teams remove\|join\|clear` |
| `bingo.admin.history` | `/bingo history [page\|id]`, `/bingo history clear`, `/bingo export <id>` |
| `bingo.admin.reload` | `/bingo reload`: rereads `config.yml`, `arenas.yml`, `presets.yml` and the language files |
| `bingo.admin.season` | `/bingo season [status\|phase\|reset\|restore\|gift\|preview]` |

Arenas (`bingo.admin.arena`):

- `/bingo arena`: the arena menu for players, the list for the console.
- `/bingo arena create <id> [preset]`: a new arena with the round settings from `config.yml`, plus the preset. Ids use `a-z`, `0-9`, `_` and `-`.
- `/bingo arena edit <id>`: the editor. Clicks change toggles and cycles; the name, the seed and presets are typed in chat, and `cancel` backs out without saving.
- `/bingo arena set <id> <setting> <value>`: changes one setting from chat or the console. A wrong value says what the setting takes. Settings: `name`, `seed`, `mode`, `win-mode`, `lockout`, `time-limit`, `team-size`, `max-teams`, `countdown`, `lives`, and on Modern also `border`, `pvp`, `keep-inventory` and `daylight` (`cycle`, `day`, `night`).
- `/bingo arena preset <id> <preset>` and `/bingo arena savepreset <id> <name>`: apply a preset, or save an arena's settings as one.
- `/bingo arena select <id>`: the arena signs and `/bingo start` use next.
- `/bingo arena setwaiting <id> [clear]`: a fixed waiting room where you stand, or back to the default.
- `/bingo arena delete <id> confirm`: deletes the arena (and its world on Modern).
- `/bingo arena prepare <id>` and `/bingo arena reset <id>` (Modern): make the arena's world ahead of time, or delete it so the next round starts on a fresh one.
- `/bingo arena list`: every arena, with the selected one marked.

`bingo.update` (op by default) receives update notices on join.

## Configuration

Files live in `plugins/BingoRush/`. New keys are added to existing files on startup and your values are kept; keys a version no longer uses are dropped at the same time.

- `config.yml`: round defaults (game mode, win mode, lockout, lives, time limit, countdown, team size), teams, item and advancement pools, sounds, round chat, the advancements tab, scoreboards, update checks, history and the optional season.
- `arenas.yml`: one entry per arena with the same settings as `/bingo arena set`. A value that does not parse keeps its default and is reported in the console; a file that does not parse is left untouched and nothing is saved until it is fixed.
- `presets.yml`: named sets of arena settings for `/bingo arena create` and the editor. `solo`, `duos`, `squads`, `lockout`, `quick`, `hardcore`, `relaxed` and `night-raid` ship with the plugin.
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
| `lobby.world` | `""` | World name for the lobby spawn point. Set via `/bingo setlobby`. | None. |
| `empty-world-cleanup-seconds` | `300` | Seconds an empty arena world stays loaded before it is deleted. 0 keeps them until restart. | Modern only. |
| `match-voting.enabled` | `true` | Gives players in a waiting room the item to vote the round. | Modern only. |
| `match-voting.options` | mode, win-mode, time-limit, lockout, lives, pvp, daylight | Arena settings on the ballot, up to seven. | Modern only. |
| `team-display` | on, `<team_color><b><tag></b> ` | Prefix in front of names for the lobby, the round and spectators. | Prefixes are cut to 16 characters before 1.13. |
| `scoreboards.<lobby\|waiting\|playing>` | on | Sidebar for each stage; turn one off when another plugin owns the sidebar. | None. |
| `season.enabled` | `false` | Optional holiday extras between the dates in `season.*`: lobby snow and snowflakes, claim fireworks, a gift hunt, a season header in the player list, a countdown to `season.lobby.highlight-day` with fireworks on the day, and a New Year title. Month-day dates (`12-01`) come back every year, read in `season.timezone`. Everything it places is put back when it ends or is turned off. | Legacy shows its particles only up to 1.12. |
| `update-notifier.enabled` | `true` | Checks GitHub releases for newer versions. | None. |
| `update-notifier.on-join` | `true` | Alerts admins with permission on server join. | None. |
| `update-notifier.check-interval-hours` | `24` | Hours between automatic background checks. 0 disables. | None. |
| `history.enabled` | `true` | Saves match summaries to `data/matches/` as JSON. | None. |
| `history.max-saved-matches` | `50` | Maximum match records to retain on disk. | None. |

## Placeholders and API

When PlaceholderAPI is installed, BingoRush exposes `%bingorush_*%` placeholders:

- `%bingorush_in_game%`: `yes` or `no` if the player is participating in an active round.
- `%bingorush_has_team%`: `yes` or `no` if the player has chosen a team.
- `%bingorush_team%`: Colored team name.
- `%bingorush_team_name%`: Plain team name without color.
- `%bingorush_team_color%`: Hex color code of the team.
- `%bingorush_team_prefix%`: Team prefix with brackets and team color.
- `%bingorush_team_score%`: Current claimed cells of the player's team.
- `%bingorush_team_members%`: Member count of the player's team.
- `%bingorush_lives%`: Remaining lives for the player (`∞` if unlimited).
- `%bingorush_status%`: Game state (`WAITING`, `COUNTDOWN`, `RUNNING`, `PAUSED`).
- `%bingorush_players_count%`: Total players currently assigned to teams.
- `%bingorush_max_players%`: Maximum capacity across all teams.
- `%bingorush_time_left%`: Formatted remaining time (`mm:ss`).
- `%bingorush_leading_team%`: Name of the team currently in the lead.

Other plugins can access the public API through Bukkit's `ServicesManager`:

```java
BingoRushAPI api = Bukkit.getServicesManager().load(BingoRushAPI.class);
```

Custom Bukkit events in `com.jifercraft.bingo.api.events`:
- `BingoRoundStartEvent`: fires when a round starts.
- `BingoRoundEndEvent`: fires when a round concludes.
- `BingoClaimEvent`: fires when a team claims an item or advancement cell.
- `BingoTeamJoinEvent`: fires when a player joins a team.
- `BingoTeamLeaveEvent`: fires when a player leaves a team.

## Upgrading from 0.1.x and the 0.2.0 previews

- Settings, teams, language files and a round saved by a crash or a restart carry over. A saved round from before arenas resumes in the main world, where it was played.
- The `maps` section of the 0.2.0 previews moves to `arenas.yml` on the first start, before `config.yml` is updated. Values a map did not set come from the round settings of the same file.
- Those maps become arenas, so on the Modern jar `/bingo start` plays in the selected arena's own world from then on, not in the main world. Delete the arenas (`/bingo arena delete <id> confirm`) to keep playing in the main world.
- The setup panel (`/bingo setup`, `/setup`) is gone: arenas and their editor replace it. Its language keys are removed from your language files on the first start.
- Folders named `bingo_match_*` under `world/dimensions/minecraft/`, left by test builds of the Modern jar, are reported in the console and not deleted; remove them by hand with the server stopped.

## Known limits on Legacy

- **Arenas**: Legacy plays in the server's own worlds. An arena there is a set of rules and a waiting room; the border, PvP, keep-inventory and daylight settings are kept in `arenas.yml` for the Modern jar and ignored, and there is no voting.

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
