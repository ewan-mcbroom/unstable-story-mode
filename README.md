# Unstable SMP Story Mode

Unofficial fan mod. Play through the Unstable SMP videos as story episodes, fighting VexBot NPCs in the characters' real kits. Not made by or affiliated with the Unstable SMP creators.

**Status:** early prototype. One draft episode: Wemmbu #33, *Escaping Prison on the Unstable SMP*.

## Get the mod

Every push builds the mod automatically. Open the **Actions** tab, click the newest green run, and download **unstable-story-mode** under Artifacts. Unzip it and use the `.jar` without `-sources` in its name.

## Install (Minecraft Java 1.21.11)

1. Fabric Loader 0.20.1 or newer, and Fabric API for 1.21.11.
2. [VexBot](https://modrinth.com/mod/vexbot) for 1.21.11.
3. [Unstable Generation](https://modrinth.com/mod/unstable-generation) for 1.21.11 (not used yet).
4. This mod's `.jar` in your `mods` folder.
5. That's it for kits: when Minecraft starts, the mod copies its kits into the `vexbot_kits` folder in your Minecraft folder, where VexBot reads them.

## Play

| Command | Does |
| --- | --- |
| `/story list` | Show the episodes |
| `/story start wemmbu_33_escaping_prison` | Start an episode. Your items are saved and come back at the end |
| `/story status` | Where you are in the episode and what to do now |
| `/story next` | Skip the current objective (for parts not built yet) |
| `/story retry` | Back to the last checkpoint |
| `/story stop` | Leave the episode |
| `/story reload` | Reload episodes and the VexBot command file |

Episodes that travel (such as the Far Lands one) need a world created with Unstable Generation installed. The mod then finds open ground for each scene by itself. In a bot `spawn`, `x` is blocks in front of the player, `z` is blocks to their right, and characters are moved to the nearest safe ground.

## Fixing VexBot commands without rebuilding

VexBot's commands aren't documented, so the mod writes them to `config/unstablestory/vexbot_commands.json` the first time it runs. If a bot doesn't spawn, fix the command there and run `/story reload`. `{name}`, `{team}`, `{kit}`, `{x}`, `{y}`, `{z}`, `{skin}`, `{target}` and `{radius}` are filled in by the mod.

## Editing episodes without rebuilding

Copy an episode file from `src/main/resources/data/unstablestory/episodes/` into `config/unstablestory/episodes/`, edit it, and run `/story reload`. Your copy replaces the built-in one.

Bots in a `spawn` step can be `"hostile": false` (peaceful, never fights) or `"ally": true` (fights on your side).

Step types: `part` (starts a new part: `number`, `title`), `title`, `say`, `narrate`, `wait`, `teleport`, `effect`, `clear_effects`, `player_kit`, `spawn`, `fight`, `despawn_all`, `objective`, `checkpoint`, `command`, `note`.

## Kits

`src/main/resources/data/unstablestory/kits/` holds the VexBot kits used by Wemmbu's episodes, picked from a community kit pack. Their original creators are unknown, so keep this repository private until we know they can be shared.
