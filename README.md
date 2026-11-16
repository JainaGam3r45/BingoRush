# BingoRush

Bingo minigame for Minecraft event servers running Paper 26.3.

Teams compete on a shared 5x5 bingo card to collect items scattered around the world. The first team to complete a line or the entire card wins the round.

## How it works

- Cards are generated from an item pool split into difficulty tiers (easy, medium, hard, epic).
- Seeds can be numeric or plain text (such as an event name), which makes it easy to replay boards or run identical cards across multiple instances.
- Win modes include line completion (rows, columns and diagonals) or full card completion.
- When time expires without a completion, the leader is the team with the most claimed items, with ties broken by the earlier claim.

## Requirements

- Paper 26.3 or newer
- Java 25 or newer

## Building

Build the jar using the Gradle wrapper:

```bash
./gradlew build
```

The compiled plugin jar will be generated in `build/libs/BingoRush-0.1.0.jar`.

## Commands and permissions

Player commands:

- `/bingo`: displays available commands and plugin help.
- `/bingo join [team]`: joins a team, or the next available team if omitted (`bingo.play`).
- `/bingo card`: opens the round card, with each cell showing who claimed it (`bingo.play`).
- `/bingo leave`: leaves the current team (`bingo.play`).

Admin commands (`bingo.admin`):

- `/bingo start [seed]`: starts a new round with an optional seed.
- `/bingo stop`: stops the active round.
- `/bingo pause`: pauses the round in progress.
- `/bingo resume`: resumes a paused round.
- `/bingo reroll`: prepares a new card for the next round.
- `/bingo teams <add|remove|join|clear>`: manages teams and player assignments.
- `/bingo reload`: reloads `config.yml` and `messages.yml`.

### Permissions

- `bingo.play`: allows joining teams and playing the game (default: true).
- `bingo.admin`: allows starting, stopping and configuring rounds (default: op).
- `bingo.update`: receives update notifications on join when a new version is available (default: op).

## Configuration

Settings are stored in `plugins/BingoRush/`:

- `config.yml`: game settings, round duration, win mode and item pool tiers with their cell counts.
- `messages.yml`: player-facing messages in MiniMessage format, with an editable color palette at the top.

## License and credits

Distributed under the MIT License. See [LICENSE](LICENSE) for details.

Developed and maintained by [JainaGam3r45](https://github.com/JainaGam3r45). Infrastructure provided by [JiferCloud Hosting](https://www.jifercraft.com).
