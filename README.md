# Virtual Spawners (Fabric, server-side, Minecraft 26.3)

Spawner blocks that never spawn mobs. They store drops and XP; right-click to collect.
Vanilla clients can join: no custom blocks or items are registered.

## Use
- Craft a spawner (recipes below) and place it.
- **Right-click** the placed spawner: storage menu (Collect Drops / Collect XP / Collect Everything).
- **Shift + right-click** with the same type of spawner item: stack it into the placed one.
- **Mine it** (owner only by default): you get the spawner item(s) back plus any stored drops and XP.
- Admins can hand out spawners with: `/give @s spawner[custom_data={vspawner:"skeleton"}] 1`
  (types: skeleton, zombie, creeper, wither_skeleton, blaze, slime, enderman)

## Recipe (all types)
```
I O I      I = iron block
X C X      O = obsidian
I O I      X, C = see table
```
| Type | X (sides) | C (centre) |
|---|---|---|
| Skeleton | bone block | ender eye |
| Zombie | rotten flesh | ender eye |
| Creeper | TNT | ender eye |
| Wither skeleton | bone block | wither skeleton skull |
| Blaze | blaze rod | ender eye |
| Slime | slime block | ender eye |
| Enderman | ender pearl | ender eye |

Recipes are plain datapack files in `data/virtualspawners/recipe/`.

## Config: `config/virtualspawners/config.json`
Interval, stack scaling, storage caps, owner-only rule, and the drops/XP of every type.
Placed spawners are saved in `config/virtualspawners/spawners.json` (keep it on a persistent volume).
Production is calculated when a spawner is opened, from elapsed time (capped by `maxStoredHours`).

## Build
GitHub Actions builds the jar with Java 25 on every push; download it from the run's artifacts.
