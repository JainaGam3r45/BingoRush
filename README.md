# BingoRush

Bingo minigame for Minecraft event servers running Paper 26.3.

Teams compete on a shared 5x5 card. The first team to complete a line or the whole card wins the round.

## How it works

- Two game modes: collect items, or complete vanilla advancements during the round.
- Cards come from a pool split into difficulty tiers (easy, medium, hard, epic). Seeds can be numbers or plain text, such as an event name, so a board can be replayed or shared between servers.
- Win by completing a line (row, column or diagonal) or the full card.
- Lockout makes every cell exclusive: the first team to claim it keeps it, and on a full card 13 cells win.
- Lives turn deaths into knockouts. A player who runs out watches as a spectator, and the last team standing wins. One life is hardcore.
- When time runs out, the team with the most cells wins, and the earlier last claim breaks a tie.
- The card also shows up as its own tab in the advancements screen, with your team's cells marked as done.
- An unfinished round is saved to disk and comes back paused after a crash or restart.
- Finished matches are logged to `data/matches/` and can be browsed in game.

## Requirements

- Paper 26.3 or newer
- Java 25 or newer

## Building

```bash
./gradlew build
```

The jar ends up in `build/libs/`, named after the version, for example `BingoRush-0.2.0.jar`.

## Commands and permissions

Player commands (`bingo.play`):

- `/bingo` or `/b`: help.
- `/bingo join [team]`: joins a team, or lets you pick one when several have room.
- `/bingo teams` or `/teams`: team selector menu.
- `/bingo card` or `/card`: opens your team's card.
- `/bingo coords` or `/bingo c`: sends your coordinates to your team.
- `/bingo leave`: leaves your team.
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

## License and credits

Distributed under the MIT License. See [LICENSE](LICENSE) for details.

Developed and maintained by [JainaGam3r45](https://github.com/JainaGam3r45). Infrastructure provided by [JiferCloud Hosting](https://www.jifercraft.com).
