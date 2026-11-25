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
5. Load the kits in `kits/` into VexBot so the kit names match (for example `prisoner`, `sargelaw_prison_break_bot`).

## Play

| Command | Does |
| --- | --- |
| `/story list` | Show the episodes |
| `/story start wemmbu_33_escaping_prison` | Start an episode. Your items are saved and come back at the end |
| `/story next` | Skip the current objective (for parts not built yet) |
| `/story retry` | Back to the last checkpoint |
| `/story stop` | Leave the episode |
| `/story reload` | Reload episodes and the VexBot command file |

The episode happens around where you stand when you start it. Use a flat creative test world for now.

## Fixing VexBot commands without rebuilding

VexBot's commands aren't documented, so the mod writes them to `config/unstablestory/vexbot_commands.json` the first time it runs. If a bot doesn't spawn, fix the command there and run `/story reload`. `{name}`, `{team}`, `{kit}`, `{x}`, `{y}`, `{z}`, `{skin}`, `{target}` and `{radius}` are filled in by the mod.

## Editing episodes without rebuilding

Copy an episode file from `src/main/resources/data/unstablestory/episodes/` into `config/unstablestory/episodes/`, edit it, and run `/story reload`. Your copy replaces the built-in one.

Step types: `title`, `say`, `narrate`, `wait`, `teleport`, `effect`, `clear_effects`, `player_kit`, `spawn`, `fight`, `despawn_all`, `objective`, `checkpoint`, `command`, `note`.

## Kits

`kits/` holds the VexBot kits used by Wemmbu's episodes, picked from a community kit pack. Their original creators are unknown, so keep this repository private until we know they can be shared.
