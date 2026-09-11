# v1.4 — three new legendaries and two new abilities

Same rules as the first seven: server-side only, vanilla clients, one of each on the
world, one per player, identified by `cw_weapon` in `custom_data`, a 3D model in the
optional pack, a 10-frame attack animation, and world effects built from block displays.

## New weapons

### Dawnbreaker — golden sword, the sun
- id `dawnbreaker`, base `minecraft:golden_sword`, name colour GOLD bold. Unbreakable
  (a gold sword would die in a day otherwise).
- Stats: 9.0 damage, 1.7 speed.
- Passive **Solar Brand**: every hit sets the target alight for 3 s. While it is bright
  outside (`Level.isBrightOutside()`) hits deal +2.0 extra magic damage.
- Passive **Daylight**: a kill heals the wielder 4.0 (two hearts).
- Ability **Sunstrike** (right-click, cooldown 15 s): the point the player is looking at,
  up to 24 blocks away (a block or an entity), is marked with a rising ring of light for
  1 s (20 ticks), then a pillar of sunlight slams down on it: everything living within
  3 blocks takes 9.0 magic damage (undead — `isInvertedHealAndHarm()` — take double) and
  is set alight for 4 s. The wielder is never hit by their own strike.
- Log lines: `ABILITY sunstrike player=<n> at=<x>,<y>,<z> hits=<k>`,
  `ABILITY solar player=<n> victim=<v> bonus=<d>`, `ABILITY daylight player=<n> heal=<h>`.
- Recipe (4-3-1): A ochre_froglight x4, B blaze_rod x3, C totem_of_undying x1, W golden_sword.
- Altar: pillar OCHRE_FROGLIGHT, accent GOLD_BLOCK.
- Animation: `slash`. Model: a broad golden blade with a sun disc in the guard, rays
  etched up the flat; the attack-frame FX is a sun-flare fan (gold/white).
- World effects: `sun_telegraph` (20 ticks, at a point: a thin gold ring rising from the
  ground and tightening, plus a faint vertical shaft), `sunstrike` (30 ticks, at a point:
  a tall glowing column of light dropping from ~14 blocks up, flattening into a ring of
  gold slabs that expands and fades, a few glowing embers arcing out).

### Voidreaper — netherite hoe, the scythe
- id `voidreaper`, base `minecraft:netherite_hoe`, name colour DARK_PURPLE bold.
- Stats: 10.0 damage, 1.3 speed.
- Passive **Wither**: every hit applies Wither I for 2 s (40 ticks).
- Passive **Soul Harvest**: a kill with it heals the wielder 4.0 and gives 4 absorption
  (Absorption I, 10 s).
- Ability **Rift** (right-click, cooldown 12 s): the first living thing along the look ray
  within 12 blocks is the mark. The wielder is torn through the void and appears right
  behind it (1.5 blocks behind, facing it; a safe spot is searched around that point,
  falling back to beside it). For the next 2 s (40 ticks) their first hit on anything is a
  **Backstab**: +6.0 magic damage. No mark on the ray: nothing happens, no cooldown, the
  action bar says "Rift  no target".
- Log lines: `ABILITY rift player=<n> victim=<v>`, `ABILITY backstab player=<n> victim=<v> bonus=<d>`,
  `ABILITY harvest player=<n> victim=<v> heal=<h>`.
- Recipe: A crying_obsidian x4, B echo_shard x3, C sculk_catalyst x1, W netherite_hoe.
- Altar: pillar CRYING_OBSIDIAN, accent SCULK.
- Animation: `slash` (a wide reaping arc). Model: a long dark haft with a curved
  crescent blade sweeping off the top, purple void-light along its inner edge, a
  sculk-teal knot at the socket; attack-frame FX a purple crescent trail.
- World effects: `rift_open` (12 ticks, follows nothing, at a point: a tear of
  crying-obsidian and purpur shards spiralling inward and closing, played both where the
  wielder left and where they arrived), `soul_harvest` (16 ticks, at the victim: four
  pale-green soul-lantern wisps rising and drifting upward, fading).

### Starfall — mace, the meteor
- id `starfall`, base `minecraft:mace`, name colour LIGHT_PURPLE bold.
- Stats: 8.0 damage, 0.7 speed (vanilla mace is 6.0 / 0.6). The vanilla smash attack still
  works exactly as it does on a mace; this sits on top of it.
- Ability **Comet** (right-click, cooldown 20 s): the wielder is launched straight up
  (velocity y = 1.5, ≈14 blocks) with fall immunity for 6 s, and is "falling as a comet"
  for the next 5 s (100 ticks). While falling as a comet, the first melee hit **or**
  touching the ground again is the **Impact**: everything living within 4 blocks (not the
  wielder) takes 8.0 magic damage, is knocked away from the point and set alight for 2 s.
  A hit that lands the impact still gets the vanilla smash damage on top.
- Passive **Heavy**: melee hits with it knock back 50 % further (an extra knockback
  push of 0.5 along the attacker's look direction).
- Log lines: `ABILITY comet player=<n>`, `ABILITY impact player=<n> hits=<k> at=<x>,<y>,<z>`.
- Recipe: A magma_block x4, B amethyst_shard x3, C nether_star x1, W mace.
- Altar: pillar POLISHED_BLACKSTONE_BRICKS, accent AMETHYST_BLOCK.
- Animation: `slam`. Model: a heavy squat head of dark stone with amethyst crystals
  jutting from it and magma cracks glowing across the faces, on a short banded haft;
  attack-frame FX a fiery halo/burst (orange/violet).
- World effects: `comet_launch` (10 ticks, at a point: a ring of magma/blackstone dust
  blown outward at ground level with a short upward burst of embers), `meteor_impact`
  (30 ticks, at a point: a flash — a bright orange cube expanding and vanishing in 4
  ticks — then magma and blackstone chunks arcing out in a ring, a flat expanding ring of
  basalt slabs, and amethyst shards flung up and falling).

## New abilities on existing weapons

### Bloodletter — Exsanguinate (right-click, cooldown 20 s)
Every bleed the wielder owns within 8 blocks bursts: its whole remaining damage budget
lands at once as one magic hit, the bleed ends, and the wielder heals 2.0 per stack burst.
Nothing bleeding nearby: "Exsanguinate  nothing bleeding", no cooldown.
Log: `ABILITY exsanguinate player=<n> victims=<k> damage=<d> heal=<h>`.
World effect `blood_burst` (12 ticks, at the victim: a red burst — redstone-block shards
flung out and down, a dark-red ring at chest height expanding and fading).

### Aegis Hammer — Stagger (passive)
Every third hit within 3 s of the last staggers the target: a 1 s (20 ticks) stun (not
rigid — it still takes knockback), a short ring of gold sparks around its head.
Log: `ABILITY stagger player=<n> victim=<v>`.
World effect `stagger` (20 ticks, follows the victim: three small gold-block sparks
orbiting the head at 2.2 blocks, shrinking away).

## Pack
- `BASE_ITEM`: dawnbreaker golden_sword, voidreaper netherite_hoe, starfall mace.
- `VANILLA` entries for golden_sword, netherite_hoe, mace (plain `minecraft:model`).
- `WEAPON_ANIM`: dawnbreaker slash, voidreaper slash, starfall slam.
- `WEAPON_FX`: dawnbreaker fx_slash-like sun flare, voidreaper crescent trail, starfall fx_slam.
- pack.mcmeta description: "CustomWeapons: 3D models for the ten legendaries".
- Display transforms: HANDHELD for all three (the mace's vanilla model is a 3D model in a
  HANDHELD-ish pose already; ours replaces it).
