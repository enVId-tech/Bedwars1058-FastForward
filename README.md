# BedWars1058 FastForward

An addon for [BedWars1058](https://github.com/andrei1058/BedWars1058) that lets admins control the game clock and events while a match is running. It's meant for testing, events and debugging.

## Goals

- Speed up, slow down or pause the game timer.
- Skip to, or go back to, any game event (generator upgrades, beds destroyed, sudden death, game end).
- Change how long until an event happens.
- Set the diamond and emerald generators to any tier, up or down.

## Requirements

- BedWars1058 (tested with 25.5-SNAPSHOT) on Spigot or Paper.
- Permission: `bwff.admin` (op by default).

## Commands

Main command: `/bwff` (aliases: `/ff`, `/fastforward`)

`[arena]` is optional if you're standing in the arena. Times can be written as `90`, `90s`, `1:30` or `2m`.

| Command | What it does |
|---|---|
| `/bwff status` | Shows speed, next event, generator tiers and timers |
| `/bwff speed <x>` | Sets game speed, e.g. `2`, `0.5`, `10` |
| `/bwff speed pause` | Pauses the game clock |
| `/bwff speed resume` | Back to normal speed (same as `reset`) |
| `/bwff timer <diamond\|emerald\|beds\|dragon\|end> <time>` | Sets a countdown. Use `+30` or `-30` to adjust it |
| `/bwff event <d2\|e2\|d3\|e3\|beds\|dragon\|end> [time]` | Jumps to an event, firing in `time` seconds |
| `/bwff tier <diamond\|emerald> <1\|2\|3\|+\|-> [time]` | Sets the middle generators to a tier |
| `/bwff skip` | Fires the next event right now |
| `/bwff rewind [time]` | Goes back one event |
| `/bwff debug` | Checks that this plugin matches your BedWars1058 version |

### Event names

`d2` Diamond II, `e2` Emerald II, `d3` Diamond III, `e3` Emerald III, `beds` beds destroyed, `dragon` sudden death, `end` game end.

## Notes

- Speed affects the whole game, including generator drops and respawn timers.
- Destroyed beds can't be restored by rewinding.
- Jumping straight to `end` skips the dragons.
- Run `/bwff debug` first on a new BedWars1058 version.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.