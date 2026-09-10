# Custom Weapons

Seven craftable weapons with abilities for **Minecraft 1.21.11 Fabric**. Built for an SMP:
**one of each weapon exists on the whole world**, forged once, at one of seven temples that
generate somewhere out in the wild.

**Players install nothing.** The mod is server-side only: no client entrypoint, no new
registry entries, no custom recipe serializer, no resource pack. Everyone joins with a
completely vanilla client, crafts these in an ordinary crafting table, and hears and sees
every ability in vanilla sounds and particles.

Built and verified against a real 1.21.11 dedicated server — see [Testing](#testing).

---

## The weapons

| Weapon | Base item | Attack damage | Attack speed | DPS | Cooldown |
|---|---|---|---|---|---|
| **Bloodletter** | netherite sword | 2.0 | 2.0 | 4.0 swings + 6.0 bleed = 10.0 | none |
| **Gale Edge** | diamond sword | 5.95 | 1.8 | 10.7 | 8 s |
| **Stormpiercer** | bow | arrow + 4.0 on shock, lightning | — | — | 6 s |
| **Aegis Hammer** | netherite axe | 9.0 | 0.9 | 8.1 | 15 s |
| **Frostbrand** | iron sword | 6.5 | 1.4 | 9.1 + shatter | none |
| **Tidecaller** | trident | 8.0 | 1.1 | 8.8 | 10 s |
| **Hellfire** | crossbow | bolt + blast | — | — | 8 s |

A vanilla netherite sword is 12.8 DPS, a netherite axe 10.0, an iron sword 9.6 and a trident
9.9. Every weapon here sits below its vanilla counterpart on raw damage — the ability is what
the crafting cost buys. The one exception is the Stormpiercer against the two mobs it
executes, which is the point of it.

### Bloodletter — sustained bleed

2.0 a hit is a quarter of the sword it is built on. The fast swing exists to stack bleed.

- Every hit applies or refreshes **Bleed** for 3 s, stacking to 3.
- Bleed deals **1.0 per stack every 0.5 s** — 2.0 DPS a stack, 6.0 DPS at three.
- Each bleed carries an **8.0 damage budget**; when it is spent the bleed ends, and a fresh
  hit refills it. Without that cap, a bleed whose timer every hit refreshes never stops.
- One bleed per victim. A second Bloodletter user takes over the existing one rather than
  running a parallel stack. Cleared by milk, death and a dimension change.
- Bleed kills are credited to whoever applied it, even if they log out mid-bleed.

### Gale Edge — mobility duellist

- **Dash** (right-click, 8 s): launches you along your look direction, Y clamped to 0.3–0.8
  so looking up is not a rocket. 6 s of fall immunity.
- The next hit within 2 s is a **Momentum Strike**, +2.0 damage.
- One air dash. Airborne with it spent, the click does nothing and does **not** eat the
  cooldown.

### Stormpiercer — ranged shock

- A **fully drawn** hit deals +4.0, **calls a lightning bolt down on the target**, applies
  Glowing for 6 s, and chains to one other entity within 5 blocks for 3.0. Partial draws fire
  as an ordinary bow.
- A fully drawn hit **kills a creeper or a skeleton outright** (`shock_instakill`, a list of
  entity ids, never players). The kill goes through the normal death path, so it is credited
  to the shooter and drops loot and experience.
- The bolt is **visual only** by default: flash and thunder, but no fire, no charged creeper
  and no villager turned witch. `shock_lightning_fire: true` makes it a real bolt that does
  all of that.
- 6 s cooldown, charged on the hit, so a miss costs nothing. The bow keeps firing normal
  arrows while the shock recharges.
- **It cannot be enchanted at all.** The `enchantable` component is stripped so an enchanting
  table offers it nothing, and any enchantment that arrives another way — an anvil, `/enchant`
  — is stripped off again on the next sweep. You are trading Power V, Flame and Infinity for
  the shock and the mark.

### Aegis Hammer — area control

- **Ground Slam** (right-click on the ground, 15 s): 4.0 damage, Slowness II for 4 s and a
  small pop-up to everything within 5 blocks with line of sight; Resistance I for 5 s to you.
- In mid-air it does nothing and does not eat the cooldown.
- Built on a netherite **axe**, not a mace: a mace's fall-distance smash would stack on top
  of the slam and blow past the balance ceiling. The axe keeps the vanilla shield disable.

### Frostbrand — freeze and shatter

- Every hit adds **frost** — vanilla's own powder-snow freeze counter, so a player sees the
  frost creep in from the edges of the screen and a mob shivers — and Slowness II for 2 s.
- Frost thaws at vanilla's rate, so it is a matter of hitting faster than the target thaws.
  Three hits in a row freeze the target solid; the third one **shatters**: +4.0 damage,
  Slowness IV for 2 s, and the frost resets.
- No cooldown and no state of its own: the counter lives on the entity and vanilla cleans it
  up. Built on an iron sword, so it is the cheapest base and the most fragile.

### Tidecaller — harpoon

- A **thrown** hit drags the target to you (10 s cooldown, charged on the hit, so a miss costs
  nothing). The throw itself is vanilla's, Loyalty and Riptide included.
- Thrown or swung, anything standing **in water or rain** takes +2.0.
- 8.0 a hit, under a vanilla trident's 9.0.

### Hellfire — exploding bolts

- The first bolt after the cooldown is armed on the shot and **explodes where it lands**, on
  an entity or in the ground. Power 1.5: about 11 before armour on a direct hit, falling off
  fast. 8 s cooldown, charged on the shot, because a bolt that lands somewhere is not a miss.
- **Breaks no blocks.** On an SMP a ranged block-breaker is a griefing tool with extra steps.
  It does hurt the shooter at their own feet, the same as TNT would.
- Multishot fires three bolts in one tick and only the first is armed, so a volley is one
  blast, not three.

---

## Recipes

All seven fill the whole grid, with the base weapon in the centre, so none can collide with or
shadow a vanilla recipe. The shape is the same every time — **4 in the corners, 3 around the
weapon, 1 underneath it** — and every slot is something you have to go somewhere dangerous for.

```
Bloodletter                                          Gale Edge
ghast tear    wither skull    ghast tear             breeze rod   shulker shell  breeze rod
wither skull NETHERITE SWORD  wither skull           shulker shell DIAMOND SWORD shulker shell
ghast tear  netherite ingot   ghast tear             breeze rod    heavy core    breeze rod

Stormpiercer                                         Aegis Hammer
lightning rod amethyst block  lightning rod          crying obsidian echo shard crying obsidian
amethyst block     BOW        amethyst block         echo shard   NETHERITE AXE  echo shard
lightning rod heart of the sea lightning rod         crying obsidian  totem      crying obsidian

Frostbrand                                           Tidecaller
blue ice   prismarine crystals   blue ice            nautilus shell  sea lantern  nautilus shell
prismarine crystals IRON SWORD prismarine crystals   sea lantern      TRIDENT     sea lantern
blue ice  enchanted golden apple blue ice            nautilus shell    conduit    nautilus shell

Hellfire
blaze rod    magma block    blaze rod
magma block   CROSSBOW     magma block
blaze rod    nether star    blaze rod
```

| Weapon | Price, on top of the base weapon | Where that sends you |
|---|---|---|
| Bloodletter | 3 wither skeleton skulls, 4 ghast tears, 1 netherite ingot | nether fortresses |
| Gale Edge | 3 shulker shells, 4 breeze rods, 1 heavy core | end cities and trial chambers |
| Stormpiercer | 3 amethyst blocks, 4 lightning rods, 1 heart of the sea | geodes and buried treasure |
| Aegis Hammer | 3 echo shards, 4 crying obsidian, 1 totem of undying | ancient cities and a raid |
| Frostbrand | 3 prismarine crystals, 4 blue ice, 1 enchanted golden apple | ocean monuments, ice spikes, and loot chests |
| Tidecaller | 3 sea lanterns, 4 nautilus shells, 1 conduit | ocean monuments and drowned |
| Hellfire | 3 magma blocks, 4 blaze rods, 1 nether star | nether fortresses and the Wither |

You have to **already own the base weapon** — the recipe upgrades a netherite sword, diamond
sword, bow or netherite axe rather than building one from nothing. Crafting does **not** carry
over that weapon's enchantments or damage value, so do not feed it your enchanted gear.

---

## Altars

Temples that generate in the overworld. A 17x17 tiled floor on a foundation that reaches down
to solid ground, a colonnade of eight pillars in the weapon's colours carrying an architrave,
low walls with the weapon's accent block set into them as windows, four doorways, soul
braziers around the sanctum, lanterns hanging on chains between the pillars, and a stepped
roof left open in the middle so light falls on the pedestal. About 700 blocks a piece.

**The price floats above the pedestal.** Five lines of name-tag text — the weapon's name, then
one line per ingredient with the count — readable through the open roof from outside:

```
              ✦ Altar of the Bloodletter
                 1x Netherite Sword
             3x Wither Skeleton Skull
                    4x Ghast Tear
                  1x Netherite Ingot
```

That is five invisible armour stands; a vanilla client renders name tags with no resource pack
and no mod. A text display would be one entity instead of five, but its text is only reachable
through synced entity data with no public setter.

Stand at the lodestone holding everything on that list and right-click it: the altar forges
the weapon and takes the materials. Same price as the recipe, so it changes discovery, not
balance — it is the route for players who never learn the recipe.

- **Placement is deterministic from the world seed**, one temple per square of
  `altar_spacing_chunks` (default 24, roughly one every 400 blocks), the way vanilla spaces
  its structures. The same seed always makes the same map, and two are never neighbours.
- **Only newly generated chunks.** Land you have already explored will not sprout temples.
- The pillars say which weapon: red nether brick and redstone for the Bloodletter, calcite and
  packed ice for the Gale Edge, copper and amethyst for the Stormpiercer, blackstone and
  gilded blackstone for the Aegis Hammer.
- Click without the full price and it tells you what is still missing, by how many.
- **A crafted weapon is never spent as an ingredient.** The Bloodletter costs a netherite
  sword, and your Bloodletter *is* a netherite sword — the altar skips it. Between two
  ordinary candidates it takes the unenchanted one first.
- Blocks go in with `UPDATE_CLIENTS` only. Firing neighbour updates for seven hundred blocks
  would be pointlessly expensive, and would drop the chains and lanterns before the block they
  hang from exists.
- The sites are recorded in `customweapons-altars.json` in the world folder. That record, not
  the blocks, is what makes a lodestone an altar, so a player who rebuilds the shape out of
  their own lodestone gets a lodestone.

Admin commands: `/customweapon altar place <weapon>` builds one where you stand,
`/customweapon altar find` gives the nearest known one's coordinates, and
`/customweapon altar rebuild` rebuilds every known altar to the current design in place —
which is how an altar recorded under an older, smaller design gets its temple.

---

## Built for an SMP

Everything below is on by default and each part has its own switch in the config.

### One of each weapon, ever

A world holds one Bloodletter, one Gale Edge, one Stormpiercer and one Aegis Hammer. The
first one made is stamped with a serial, and that serial is recorded in
`customweapons-claims.json` in the world folder. When it is made, **the whole server is told**:

```
Tim has forged Bloodletter - the only one on this world.
```

Anyone who crafts a second one gets the plain base item back and **their materials returned**,
with a line telling them who beat them to it. Taking somebody's three wither skulls for an
item they cannot keep would be robbery rather than a rule, so the refund is not optional.

An altar whose weapon is already made is **spent, not broken**: it stays standing and names
whoever forged it. The temple becomes a monument.

- `/customweapon claims` — what has been forged, by whom, with serials
- `/customweapon unclaim weapon <weapon>` — release one so it can be made again, for the
  legendary that went into a lava pit. If the original still exists in somebody's inventory it
  simply re-claims itself on the next sweep, so an unclaim by mistake is not a duplication bug.

### One temple per weapon

With `one_altar_per_weapon`, once a weapon's temple exists somewhere in the world no second
one is ever built. A world ends up with exactly seven landmarks and finding them is the
content. Placement is still seed-deterministic and only in newly generated chunks.

### The rest of the SMP pass

- **A dropped legendary neither burns nor despawns.** Item entities carrying a custom weapon
  are made invulnerable with unlimited lifetime — otherwise a weapon that only exists once
  leaves the world for good because somebody died over lava while their friend was offline.
- **Altars cannot be mined.** The lodestone and its pedestal refuse to break, so nobody
  removes a landmark from under the server.
- **Identity is a serial, not a name.** An anvil-renamed netherite sword is not a Bloodletter,
  and neither is a copy of the real one.
- Bleed, dash state and cooldowns are per-player and in memory only; nothing leaks between
  players and nothing survives a restart that should not.

---

## Install

1. Stop the server.
2. Put `customweapons-1.0.0.jar` in `mods/`, alongside **Fabric API** (`0.141.6+1.21.11` or
   newer). `mods/` — not `world/datapacks/`; this is a mod, not a datapack.
3. Start the server. The console prints:

```
CustomWeapons ready: 4 weapons craftable
```

## Commands

| Command | What it does |
|---|---|
| `/customweapon give <players> <weapon>` | Hands out a finished weapon |
| `/customweapon list` | The seven weapons and whether each is enabled |
| `/customweapon altar place <weapon>` | Builds an altar where you stand |
| `/customweapon altar find` | Coordinates of the nearest known altar |
| `/customweapon altar rebuild` | Rebuilds every known altar to the current design |
| `/customweapon claims` | What has been forged on this world, by whom |
| `/customweapon unclaim weapon <weapon>` | Release a weapon so it can be forged again |
| `/customweapon reload` | Re-reads `config/customweapons.json` |

Permission level 2, the same level vanilla requires for `/give`.

## Config

`config/customweapons.json` holds every number: damage, attack speed, every cooldown,
bleed stacks / interval / duration / budget, dash velocity and clamps, the momentum window,
slam radius and knockback, shock range and chain, and a per-weapon `_enabled` toggle.

Damage and speed are written as the **totals a player sees**, not as attribute modifier
values — the mod subtracts the player's base 1.0 damage and 4.0 speed for you.

`/customweapon reload` re-stats weapons that are **already in players' inventories**, not
just newly crafted ones: the recipe stamps only the weapon's id, and the mod applies name,
lore, attributes and glint on a sweep, comparing a generation counter stored on the item.

The SMP switches live there too: `unique_weapons`, `one_altar_per_weapon`,
`announce_forging`, `protect_altars`, `protect_dropped_weapons`. Turn `unique_weapons` off and
it goes back to being an ordinary kit mod where anyone can craft anything.

Set `log_abilities: true` for one log line per ability trigger.

---

## 3D models (optional resource pack)

`pack/` builds a resource pack that gives each weapon its own voxel 3D model, in the
inventory and in the hand, including draw stages for the Stormpiercer and Hellfire:

```bash
python3 pack/build.py     # -> release/CustomWeapons-Models.zip and pack/preview.html
```

Open `pack/preview.html` in a browser to see every model turning.

**Players are asked in chat.** On join the mod says the server has 3D weapon models and
shows three buttons: **[Install]** sends the pack (the vanilla download dialog appears),
**[Not now]** asks again next join, **[Never]** remembers the answer in
`world/customweapons-pack-declined.json`. `/weaponpack install` works for anyone at any
time. Nothing is forced: the pack is sent as optional, so a player who declines the dialog
just keeps the plain items. The link and hash live in the config:

```json
"pack_offer_on_join": true,
"pack_url": "https://github.com/idkhowtonamemyselfasadev/customweapons/releases/download/v1.0.0/CustomWeapons-Models.zip",
"pack_sha1": "7f9a282fac0db5a08e6a4e0a7560ea50c1eb41e1",
"pack_offer_message": "This server has 3D models for the legendary weapons. Want them?"
```

The hash has to change with the file, or clients keep a stale cached copy. If you would
rather force the pack on everyone, use vanilla's `resource-pack` and `require-resource-pack`
in `server.properties` instead and set `pack_offer_on_join` to false. The mod stamps
`custom_model_data` string `cw:<weapon>` on every weapon; the pack's item definitions select
the model on that string and fall through to vanilla for everything else. Players without
the pack see the plain base items exactly as before. Models are sculpted in `pack/weapons.py`
as voxels on a 32-grid and greedily meshed; every element leans 45 degrees so vanilla's own
hand transforms apply unchanged.

## Adding an eighth weapon

One class extending `CustomWeapon`, one line in `Weapons.ALL`, one recipe JSON. Nothing else
in the mod needs to know about it.

## Build

```bash
JAVA_HOME=/home/tim/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew build
# -> build/libs/customweapons-1.0.0.jar
```

## Testing

```bash
bash runtest/test.sh
```

Boots a real 1.21.11 Fabric dedicated server on port 25603 — its own directory, so a test run
never touches the server in `run/` you are playing on — and drives three **vanilla**
mineflayer clients against it — real crafting in a real crafting table, real damage, real
packets. It asserts on both what the clients received and what the server logged:

- the recipe produces a result, and the crafted item comes back named and stamped
- name, lore and attribute modifiers arrive at the client
- bleed keeps ticking after the attacker stops, then ends on its own inside its budget
- an **anvil-renamed** netherite sword applies no bleed
- the dash sends a launch velocity to the client; a second dash on cooldown is refused
- the first hit after a dash is a Momentum Strike
- a slam in mid-air does nothing; on the ground it damages and slows a nearby player
- one arrow is armed by a full draw and none by a partial draw, and the shock lands
- the Stormpiercer refuses to be enchanted
- `/customweapon reload` re-stats a weapon already in an inventory
- the first weapon is handed out and announced to everyone; a second cannot be given by
  command; a second **crafted** one reverts to a plain sword with the materials refunded and
  the reason explained; `/customweapon unclaim` opens it up again
- a spent altar names whoever forged the weapon and takes nothing
- the altar block cannot be mined
- the temple is a real build: 680+ structural blocks, 8 hanging lanterns on 16 chains, 4
  braziers, and 68 pillar blocks in the weapon's colour
- an altar carries exactly five label entities with the weapon's name and its price on them
- an altar lists what is missing, refuses a payment one wither skull short, forges the weapon
  once the full price is in hand, takes the materials, and refuses to spend a crafted
  Bloodletter as its own base item
- altars generate in newly generated chunks (50 of them, in the run above)
- an enchantment forced onto the Stormpiercer with `/enchant` does not stick

### Running a server to play on

```bash
./run/start.sh &            # port 25602, offline mode, superflat
./run/cmd.sh op <yourname>  # anything you would type in the console
```

### Notes from testing

- **1.21.11 renamed every gamerule** to snake_case. `naturalRegeneration` is now
  `natural_health_regeneration`, `keepInventory` is `keep_inventory`. The old names fail as
  "Incorrect argument for command", which looks like nothing happened.
- mineflayer's protocol tables for 1.21.11 are behind on particles and attribute ids, so it
  logs partial-read noise for packets a real client handles fine. The harness filters it.
- A headless bot runs its own physics and ignores the velocity packet, so the dash test
  asserts on the packet the client was sent rather than on the bot moving.
- **Removing the `enchantable` component does not stop `/enchant` or an anvil.** That
  component governs the enchanting table; enchantments applied any other way go through the
  enchantment's own `supported_items` tag and land regardless. The test caught Power I going
  straight onto the Stormpiercer. The fix is a per-sweep `maintain` hook that strips any
  enchantment that appears, so the drawback holds however the enchantment arrived.
- `run/` is the server to play on and `runtest/` is the one `test.sh` wipes and rebuilds, on
  port 25603, so a test run never disturbs a live session.
- The harness keeps altars **off** until the melee tests are done. A 17x17 temple generating
  on top of spawn moves the bots off the flat ground the slam test needs, which showed up as
  a mid-air slam that was not mid-air.
- `minecraft:chain` is `Blocks.IRON_CHAIN` in 1.21.11, and a wither skeleton skull's
  translation key is `block.minecraft.*`, not `item.minecraft.*`.
- Turning uniqueness on mid-session claims whatever is being carried at that moment, so the
  harness empties both inventories before flipping the switch. The same applies on a real
  server: install the mod before handing anything out.
- The archery test retries up to three times. A bot's aim is not what is under test, and one
  miss should not fail a run.
