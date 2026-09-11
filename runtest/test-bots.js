// Drives three vanilla clients against the test server.
//
// mineflayer speaks the plain vanilla protocol and knows nothing about this mod, which is
// exactly the point: if a bot can craft the weapon, see its name and stats, and feel its
// abilities, then so can a real player on an unmodified client.
const mineflayer = require('/home/tim/claude/anticheat/test/node_modules/mineflayer');
const fs = require('fs');
// The mod's world effects are vanilla block_display entities; a client only ever sees the
// numeric type id in spawn_entity.
const MC_DATA = require('/home/tim/claude/anticheat/test/node_modules/minecraft-data')('1.21.11');
const BLOCK_DISPLAY = MC_DATA.entitiesByName.block_display.id;

const RUN = __dirname;
const PORT = parseInt(process.env.PORT || '25603', 10);
const console_fifo = fs.createWriteStream(RUN + '/console.fifo', { flags: 'a' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;

function cmd(c) {
  console_fifo.write(c + '\n');
}
function ok(name, extra = '') {
  console.log(`   PASS  ${name}${extra ? '  ' + extra : ''}`);
}
function fail(name, extra = '') {
  failures++;
  console.log(`!! FAIL  ${name}${extra ? '  ' + extra : ''}`);
}
function check(cond, name, extra = '') {
  cond ? ok(name, extra) : fail(name, extra);
  return cond;
}

function connect(username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      host: '127.0.0.1', port: PORT, username, version: '1.21.11', auth: 'offline',
    });
    bot.actionBars = [];
    bot.chats = [];
    bot.velocityPackets = [];
    bot.on('message', (msg, position) => {
      const text = msg.toString();
      bot.chats.push(text);
      if (position === 'game_info' || position === 'action_bar') {
        bot.actionBars.push(text);
      }
    });
    // Velocity packets are what a vanilla client actually applies for a dash or knockback.
    bot._client.on('entity_velocity', (p) => {
      if (bot.entity && p.entityId === bot.entity.id) bot.velocityPackets.push(p);
    });
    // World effects: block displays spawned by the handful, teleported every tick, then
    // removed. Count what this client is sent, by entity type id; the per-tick moves are
    // deliberately not recorded, only that the bot survives them.
    bot.fxSpawns = [];   // {t, id}
    bot.fxGone = [];     // {t, id}
    bot._client.on('spawn_entity', (p) => {
      if (p.type === BLOCK_DISPLAY) bot.fxSpawns.push({ t: Date.now(), id: p.entityId });
    });
    bot._client.on('entity_destroy', (p) => {
      const now = Date.now();
      for (const id of p.entityIds) {
        if (bot.fxSpawns.some((s) => s.id === id)) bot.fxGone.push({ t: now, id });
      }
    });
    // Every clientbound `position` packet: /tp, and the shock stun putting the bot back.
    bot.forcedMoves = 0;
    bot.on('forcedMove', () => bot.forcedMoves++);
    bot.once('spawn', () => resolve(bot));
    bot.on('error', reject);
    bot.on('kicked', (r) => reject(new Error('kicked: ' + JSON.stringify(r))));
  });
}

async function equipByName(bot, name) {
  const item = bot.inventory.items().find((i) => i.name === name);
  if (!item) throw new Error(`${bot.username} has no ${name}`);
  await bot.equip(item, 'hand');
  await sleep(300);
  return bot.heldItem;
}

function describe(item) {
  if (!item) return '(nothing)';
  return JSON.stringify({
    name: item.name,
    customName: item.customName ? JSON.stringify(item.customName).slice(0, 120) : null,
    components: (item.components || []).map((c) => c.type || c.name),
    removedComponentCount: item.removedComponentCount,
  });
}

// ------------------------------------------------------------------ attack animations

/**
 * Watches the frame number the server writes into the held weapon's custom_model_data
 * floats after an ability fires.
 *
 * Two views of the same packets. prismarine-item's parse of the slot is recorded as-is, but
 * minecraft-data's 1.21.11 tables mis-read `attribute_modifiers`, so a set_slot carrying a
 * sword never comes out of the deserializer at all (dropped silently, no error: mineflayer
 * hides them) and bot.heldItem stays stale. So the packet stream is tapped below the
 * deserializer, on the decompressor, where every packet body arrives parsed or not, and
 * the assertion is on the bytes: the component is
 * [0x11 custom_model_data][varint n][n x f32][0x00 no flags][0x01 one string][len]"cw:<id>"
 * and the mod writes at most one float, so the frame sits at a fixed offset in front of the
 * selector string. A body starts with its packet id: 0x14 set_slot, 0x12 window_items.
 */
function watchFrames(bot, selector) {
  const raw = [];          // {t, frame}: 0 means "no float", NaN means an unexpected layout
  const parsed = new Set();
  const started = Date.now();
  const stream = bot._client.decompressor || bot._client.splitter;
  const onRaw = (buf) => {
    if (buf[0] !== 0x14 && buf[0] !== 0x12) return;
    const idx = buf.indexOf(selector, 0, 'latin1');
    if (idx < 0) return;
    const nFlags = idx - 3;
    if (buf[idx - 1] !== selector.length || buf[idx - 2] !== 1 || buf[nFlags] !== 0) {
      raw.push({ t: Date.now() - started, frame: NaN });
      return;
    }
    let frame = NaN;
    if (buf[nFlags - 1] === 0 && buf[nFlags - 2] === 17) frame = 0;
    else if (buf[nFlags - 5] === 1 && buf[nFlags - 6] === 17) frame = buf.readFloatBE(nFlags - 4);
    raw.push({ t: Date.now() - started, frame });
  };
  stream.on('data', onRaw);
  const poll = setInterval(() => {
    const item = bot.heldItem;
    const cmd = item && (item.components || []).find((c) => c.type === 'custom_model_data');
    if (cmd) parsed.add(JSON.stringify(cmd.data !== undefined ? cmd.data : cmd));
  }, 50);
  return {
    raw, parsed,
    stop() { stream.removeListener('data', onRaw); clearInterval(poll); },
  };
}

// ------------------------------------------------------------------- world effects

/**
 * The block displays a client was sent from `since` on, within `spawnWindowMs`, and how
 * many of those it has since been told to remove within `goneWindowMs` of their spawn.
 */
function fxSince(bot, since, spawnWindowMs = 1000, goneWindowMs = 1500) {
  const spawned = bot.fxSpawns.filter((s) => s.t >= since && s.t <= since + spawnWindowMs);
  const goneAt = new Map(bot.fxGone.map((g) => [g.id, g.t]));
  const gone = spawned.filter((s) => goneAt.has(s.id) && goneAt.get(s.id) <= s.t + goneWindowMs).length;
  return { spawned: spawned.length, gone, total: bot.fxSpawns.filter((s) => s.t >= since).length };
}

/**
 * Asks the server itself, through the console, how many tagged effect displays exist right
 * now. `execute if entity` echoes "Test passed. Count: N" or "Test failed" into test.log.
 * NaN when nothing was echoed in time.
 */
async function fxCount(extraSelector = '', selectorBase = 'type=minecraft:block_display,tag=customweapons_fx') {
  const offset = fs.readFileSync(RUN + '/test.log', 'utf8').length;
  cmd(`execute if entity @e[${selectorBase}${extraSelector}]`);
  for (let i = 0; i < 20; i++) {
    await sleep(100);
    const tail = fs.readFileSync(RUN + '/test.log', 'utf8').slice(offset);
    const m = tail.match(/Test passed[.,]? [Cc]ount: (\d+)|Test passed|Test failed/);
    if (m) return m[0] === 'Test failed' ? 0 : parseInt(m[1] || '1', 10);
  }
  return NaN;
}

async function waitUntil(pred, limitMs, stepMs = 50) {
  const started = Date.now();
  while (Date.now() - started < limitMs) {
    if (pred()) return true;
    await sleep(stepMs);
  }
  return pred();
}

// ---------------------------------------------------------------------------- crafting

async function craftBloodletter(bot) {
  // Real crafting, in a real crafting table, with clicks a vanilla client could make. This is
  // the only test that proves the recipe JSON is right.
  //
  //   A B A      A = ghast tear x4
  //   B W B      B = wither skeleton skull x3
  //   A C A      W = netherite sword, C = netherite ingot
  cmd(`clear ${bot.username}`);
  cmd(`give ${bot.username} minecraft:netherite_sword 1`);
  cmd(`give ${bot.username} minecraft:wither_skeleton_skull 3`);
  cmd(`give ${bot.username} minecraft:ghast_tear 4`);
  cmd(`give ${bot.username} minecraft:netherite_ingot 1`);
  const p = bot.entity.position;
  const bx = Math.floor(p.x) + 1, by = Math.floor(p.y), bz = Math.floor(p.z);
  cmd(`setblock ${bx} ${by} ${bz} minecraft:crafting_table`);
  await sleep(1500);

  const table = bot.findBlock({ matching: (b) => b.name === 'crafting_table', maxDistance: 5 });
  if (!table) {
    fail('crafting table placed');
    return null;
  }
  const win = await bot.openBlock(table);
  await sleep(500);

  // Slot 0 is the result; 1-9 are the grid, row by row. Right-click drops exactly one, so a
  // held stack can be spread across several slots.
  const spread = async (itemName, slots) => {
    const src = win.slots.findIndex((sl, i) => i >= 10 && sl && sl.name === itemName);
    if (src < 0) throw new Error(`no ${itemName} in the window`);
    await bot.clickWindow(src, 0, 0);
    for (const slot of slots) {
      await bot.clickWindow(slot, 1, 0);
      await sleep(100);
    }
    await sleep(150);
  };
  await spread('ghast_tear', [1, 3, 7, 9]);
  await spread('wither_skeleton_skull', [2, 4, 6]);
  await spread('netherite_sword', [5]);
  await spread('netherite_ingot', [8]);
  await sleep(600);

  const result = win.slots[0];
  if (!result) {
    fail('recipe produces a result', 'result slot empty');
    bot.closeWindow(win);
    return null;
  }
  ok('recipe produces a result', `${result.name} x${result.count}`);
  await bot.clickWindow(0, 0, 1);              // shift-click the result out
  await sleep(400);
  bot.closeWindow(win);
  await sleep(400);
  return bot.inventory.items().find((i) => i.name === 'netherite_sword');
}

// ------------------------------------------------------------------------------- main

async function main() {
  console.log('== connecting three vanilla clients ==');
  const smith = await connect('Smith');
  const dummy = await connect('Dummy');
  const archer = await connect('Archer');
  await sleep(1500);

  for (const b of [smith, dummy, archer]) cmd(`op ${b.username}`);
  // 1.21.11 renamed every gamerule to snake_case; the old camelCase names are gone and
  // fail silently as "Incorrect argument for command".
  cmd('gamerule natural_health_regeneration false');
  cmd('gamerule spawn_mobs false');
  // A slime chunk under the arena keeps producing slimes regardless of the rule above (seen
  // biting the Dummy mid-measurement and swarming a Sunstrike), so sweep them every few seconds.
  const slimeSweep = setInterval(() => cmd('kill @e[type=minecraft:slime]'), 3000);
  slimeSweep.unref();
  cmd('gamerule keep_inventory true');
  cmd('gamerule immediate_respawn true');
  cmd('difficulty normal');
  cmd('time set day');
  cmd('weather clear');
  // The world is flat at y=-60 with a fresh random seed every run. When the arena lands on
  // a slime chunk, slimes spawn in the seconds between boot and the gamerule above, keep
  // existing after it, and wander in during a later section - one killed Dummy in the
  // middle of a crafting click. Clear whatever spawned before the rule took.
  cmd('kill @e[type=!minecraft:player]');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  cmd('tp Archer 0 -59 6');
  // The weapons hit hard enough that a 20-hp Dummy dies mid-section: three Bloodletter
  // swings plus their bleed alone come to ~31. Sixty covers every section here and is still
  // within what one instant_health IV (64) heals, so every heal below is a heal to full.
  cmd('attribute Dummy minecraft:max_health base set 60');
  cmd('effect give Dummy minecraft:instant_health 1 4 true');
  await sleep(2500);
  console.log(`   Smith ${smith.entity.position}`);
  console.log(`   Dummy ${dummy.entity.position}, ${dummy.health} hp`);

  // ------------------------------------------------------------- 1. crafting
  console.log('\n== 1. crafting the Bloodletter in a crafting table ==');
  const crafted = await craftBloodletter(smith);
  if (crafted) {
    await sleep(1200);   // the stat sweep runs every 5 ticks
    const held = await equipByName(smith, 'netherite_sword');
    console.log('   crafted item: ' + describe(held));
    check(held && /Bloodletter/.test(JSON.stringify(held)),
        'crafted weapon is named and stamped by the server');
  }

  // ------------------------------------------------- 2. what a vanilla client sees
  console.log('\n== 2. /customweapon give, and what the client receives ==');
  cmd('clear Smith');
  cmd('customweapon give Smith bloodletter');
  await sleep(1500);
  const bloodletter = await equipByName(smith, 'netherite_sword');
  console.log('   ' + describe(bloodletter));
  const raw = JSON.stringify(bloodletter);
  check(/Bloodletter/.test(raw), 'display name reaches the client');
  check(/attribute_modifiers/.test(raw), 'attribute modifiers reach the client');
  check(/lore/.test(raw), 'lore reaches the client');

  // --------------------------------------------------------------- 3. the bleed
  console.log('\n== 3. Bloodletter bleed ==');
  cmd('effect give Dummy minecraft:instant_health 1 4 true');
  await sleep(1200);
  const dummyStart = dummy.health;
  const target = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!target) {
    fail('Smith can see Dummy');
  } else {
    // Each hit also plays the attack animation on the held sword: frames 1..10, one a
    // tick, then the float is cleared. Watch the held slot while the swings go in.
    const anim = watchFrames(smith, 'cw:bloodletter');
    const bloodFxAt = Date.now();
    for (let i = 0; i < 3; i++) {
      smith.attack(target);
      await sleep(600);
    }
    const afterHits = dummy.health;
    console.log(`   Dummy ${dummyStart} -> ${afterHits} after three swings`);
    await sleep(4000);   // hits stopped; only the bleed is still running
    anim.stop();
    const afterBleed = dummy.health;
    console.log(`   Dummy ${afterHits} -> ${afterBleed} with nobody touching it`);
    check(afterBleed < afterHits, 'bleed keeps damaging after the attacker stops',
        `${(afterHits - afterBleed).toFixed(1)} hp`);
    await sleep(3000);
    check(Math.abs(dummy.health - afterBleed) < 0.01,
        'bleed ends on its own', `settled at ${dummy.health.toFixed(1)}`);
    check(smith.actionBars.some((m) => /Bleed x/.test(m)),
        'attacker sees the bleed stacks on the action bar',
        smith.actionBars.filter((m) => /Bleed/.test(m)).slice(-1)[0] || '');

    // The blood_slash world effect: 9 block displays per hit, gone again after 12 ticks.
    const bloodFx = fxSince(dummy, bloodFxAt, 1000, 1500);
    console.log(`   blood_slash: Dummy was sent ${bloodFx.spawned} block displays within 1s of the first swing `
        + `(${bloodFx.total} over the section), ${bloodFx.gone} of them removed within 1.5s`);
    check(bloodFx.spawned >= 9, 'a Bloodletter hit spawns the blood_slash displays (>= 9)', `${bloodFx.spawned}`);
    check(bloodFx.gone >= 9 && bloodFx.gone >= bloodFx.spawned,
        'every blood_slash display is removed again within 1.5s', `${bloodFx.gone}/${bloodFx.spawned}`);

    // The animation, as the client received it.
    const trace = anim.raw.map((s) => s.frame);
    console.log(`   held-slot updates: ${anim.raw.length}; frames on the wire: ${trace.join(',')}`);
    console.log(`   last update at +${anim.raw.length ? anim.raw[anim.raw.length - 1].t : '?'}ms; `
        + `prismarine-item's custom_model_data: ${JSON.stringify([...anim.parsed]).slice(0, 300)}`);
    const firstFrame = anim.raw.find((s) => s.frame > 0);
    const early = firstFrame
        ? new Set(anim.raw.filter((s) => s.frame > 0 && s.t <= firstFrame.t + 600).map((s) => s.frame))
        : new Set();
    check(early.size >= 3, 'the held sword steps through animation frames after a hit',
        `${early.size} distinct frames within 0.6s: ${[...early].join(',')}`);
    check(!trace.some((f) => Number.isNaN(f)), 'every frame update has the expected layout');
    const last = anim.raw[anim.raw.length - 1];
    check(!!firstFrame && !!last && last.frame === 0 && last.t > firstFrame.t + 500,
        'the frame is cleared again once the animation ends',
        last ? `last update frame=${last.frame} at +${last.t}ms` : 'no updates');

    // A weapon that leaves the main hand mid-animation (slot switch, knockback, death)
    // used to keep its last frame for good: a sword drawn stuck mid-swing. The sweep now
    // clears a frame off any weapon that is not the animating main-hand item.
    console.log('   -- switching away mid-animation');
    cmd('effect give Dummy minecraft:instant_health 1 4 true');
    await sleep(1200);
    const swordSlot = smith.quickBarSlot;
    const emptySlot = [0, 1, 2, 3, 4, 5, 6, 7, 8].find((i) => i !== swordSlot && !smith.inventory.slots[36 + i]);
    const away = watchFrames(smith, 'cw:bloodletter');
    const awayStart = Date.now();
    smith.attack(target);
    await sleep(100);
    smith.setQuickBarSlot(emptySlot);
    const switchedAt = Date.now() - awayStart;
    await sleep(1500);
    smith.setQuickBarSlot(swordSlot);
    await sleep(1000);
    away.stop();
    const awayTrace = away.raw.map((s) => s.frame);
    console.log(`   frames on the wire: ${awayTrace.join(',')}  (switched away at +${switchedAt}ms)`);
    const awayLast = away.raw[away.raw.length - 1];
    check(away.raw.some((s) => s.frame > 0), 'the animation had started before the switch');
    check(!!awayLast && awayLast.frame === 0,
        'the frame is cleared off the sword once it leaves the main hand',
        awayLast ? `last update frame=${awayLast.frame} at +${awayLast.t}ms` : 'no updates');
    // And the server's own view of the slot, echoed into test.log by a console data get.
    const logBefore = fs.readFileSync(RUN + '/test.log', 'utf8').length;
    cmd(`data get entity Smith Inventory[{Slot:${swordSlot}b}].components`);
    await sleep(1500);
    const echoed = fs.readFileSync(RUN + '/test.log', 'utf8').slice(logBefore)
        .split('\n').filter((l) => /custom_model_data/.test(l)).slice(-1)[0] || '';
    const cmdComponent = (echoed.match(/custom_model_data"?:\s*\{[^}]*\}/) || [''])[0];
    console.log('   data get: ' + (cmdComponent || echoed.slice(0, 200) || '(no echo in test.log)'));
    check(!!cmdComponent && !/floats:\s*\[\s*-?\d/.test(cmdComponent),
        "the server's copy of the sword carries no frame float", cmdComponent);
  }

  // ---------------------------------------------------- 4. a renamed sword is not one
  console.log('\n== 4. an anvil-renamed sword must not bleed ==');
  cmd('clear Smith');
  cmd(`give Smith minecraft:netherite_sword[minecraft:custom_name='{"text":"Bloodletter"}'] 1`);
  cmd('effect give Dummy minecraft:instant_health 1 4 true');
  await sleep(1500);
  await equipByName(smith, 'netherite_sword');
  smith.actionBars.length = 0;
  const spoofTarget = smith.players['Dummy'].entity;
  for (let i = 0; i < 2; i++) { smith.attack(spoofTarget); await sleep(600); }
  await sleep(1500);
  check(!smith.actionBars.some((m) => /Bleed x/.test(m)),
      'renamed sword applies no bleed');

  // ------------------------------------------------------------- 5. the Gale Edge
  console.log('\n== 5. Gale Edge dash and Momentum Strike ==');
  cmd('clear Smith');
  cmd('customweapon give Smith gale_edge');
  cmd('effect give Dummy minecraft:instant_health 1 4 true');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  await sleep(2000);
  await equipByName(smith, 'diamond_sword');
  smith.actionBars.length = 0;
  smith.velocityPackets.length = 0;

  const before = smith.entity.position.clone();
  await smith.look(0, 0, true);        // face due south, level
  const dashFxAt = Date.now();
  smith.activateItem();
  await sleep(500);
  const moved = smith.entity.position.distanceTo(before);
  const launch = smith.velocityPackets[smith.velocityPackets.length - 1];
  console.log(`   moved ${moved.toFixed(2)} blocks; velocity packets: ${smith.velocityPackets.length}`);
  if (launch) console.log(`   launch velocity ${JSON.stringify(launch)}`);
  // The dash is a velocity packet, which is what a vanilla client applies. The headless bot
  // runs its own physics and ignores it, so the assertion is on the packet it was sent.
  check(!!launch && launch.velocity && Math.abs(launch.velocity.y) > 0.2,
      'the client is sent a launch velocity',
      launch ? JSON.stringify(launch.velocity) : 'no packet');
  check(smith.actionBars.some((m) => m.trim() === 'Dash'),
      'the dash reports on the action bar');
  await sleep(600);
  const dashFx = fxSince(smith, dashFxAt, 1000, 1500);
  console.log(`   wind_dash: Smith was sent ${dashFx.spawned} block displays, ${dashFx.gone} removed within 1.5s`);
  check(dashFx.spawned >= 16, 'the dash spawns the wind_dash displays (>= 16)', `${dashFx.spawned}`);

  // Back into melee range while the Momentum window is still open.
  cmd('tp Smith 1 -59 0');
  await sleep(500);
  smith.actionBars.length = 0;
  const momentumTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  const dummyBeforeMomentum = dummy.health;
  const momentumFxAt = Date.now();
  if (momentumTarget) smith.attack(momentumTarget);
  await sleep(1200);
  const momentumFx = fxSince(dummy, momentumFxAt, 1000, 1500);
  console.log(`   wind_hit: Dummy was sent ${momentumFx.spawned} block displays, ${momentumFx.gone} removed within 1.5s`);
  check(momentumFx.spawned >= 7, 'the Momentum Strike spawns the wind_hit displays (>= 7)', `${momentumFx.spawned}`);
  console.log(`   Dummy ${dummyBeforeMomentum} -> ${dummy.health}`);
  check(smith.actionBars.some((m) => /Momentum Strike/.test(m)),
      'the first hit after a dash is a Momentum Strike',
      smith.actionBars.slice(-1)[0] || '');

  smith.actionBars.length = 0;
  smith.activateItem();                // still inside the 8s cooldown: must be refused
  await sleep(800);
  check(smith.actionBars.some((m) => /Dash\s/.test(m)),
      'a second dash on cooldown is refused with the time left',
      smith.actionBars.filter((m) => /Dash/.test(m)).slice(-1)[0] || '');

  // -------------------------------------------------------- 6. the Aegis Hammer
  console.log('\n== 6. Aegis Hammer ground slam ==');
  cmd('clear Smith');
  cmd('customweapon give Smith aegis_hammer');
  cmd('effect give Dummy minecraft:instant_health 1 4 true');
  cmd('tp Smith 1 -59 0');
  cmd('tp Dummy 3 -59 0');
  await sleep(2000);
  await equipByName(smith, 'netherite_axe');
  smith.actionBars.length = 0;

  smith.setControlState('jump', true);
  await sleep(250);
  smith.setControlState('jump', false);
  smith.activateItem();                // mid-air: must do nothing
  await sleep(1200);
  check(!smith.actionBars.some((m) => /Ground Slam\s+\d+ hit/.test(m)),
      'a slam in mid-air does nothing');

  const dummyBeforeSlam = dummy.health;
  const slamFxAt = Date.now();
  smith.activateItem();                // on the ground: must slam
  await sleep(1500);
  const slamFx = fxSince(smith, slamFxAt, 1000, 1500);
  console.log(`   slam_wave: Smith was sent ${slamFx.spawned} block displays, ${slamFx.gone} removed within 1.5s`);
  check(slamFx.spawned >= 31, 'the slam spawns the slam_wave displays (>= 31)', `${slamFx.spawned}`);
  console.log(`   Dummy ${dummyBeforeSlam} -> ${dummy.health}`);
  check(dummy.health < dummyBeforeSlam, 'slam damages a nearby player',
      `${(dummyBeforeSlam - dummy.health).toFixed(1)} hp`);
  check(Object.keys(dummy.entity.effects || {}).length > 0,
      'slam applies an effect to the target',
      JSON.stringify(Object.keys(dummy.entity.effects || {})));

  // --------------------------------------------------------- 7. the Stormpiercer
  console.log('\n== 7. Stormpiercer shock ==');
  // Every full draw fired with the shock ready arms one arrow, hit or miss. One fired inside
  // the cooldown is a plain shot: a "Shock  Ns" note on the action bar and no arm. test.sh
  // checks the server's arm count against the ready ones counted here.
  let fullDraws = 0;
  let shockHitAt = 0;
  cmd('clear Archer');
  cmd('customweapon give Archer stormpiercer');
  cmd('give Archer minecraft:arrow 16');
  cmd('effect give Dummy minecraft:instant_health 1 4 true');
  // Smith out of chain range (5 blocks), so the shock's numbers are Dummy's alone.
  cmd('tp Smith 0 -59 -12');
  cmd('tp Archer 3 -59 6');
  cmd('tp Dummy 3 -59 0');
  await sleep(2000);
  const bow = await equipByName(archer, 'bow');
  console.log('   ' + describe(bow));
  // The drawback is a stripped `enchantable` component. Prove it the way a player would
  // find out: try to enchant it. The console result is asserted on server-side.
  cmd('enchant Archer minecraft:power 1');
  await sleep(2000);
  const bowAfter = archer.inventory.items().find((i) => i.name === 'bow');
  console.log('   after /enchant: ' + describe(bowAfter));
  check(bowAfter && (bowAfter.enchants || []).length === 0,
      'an enchantment forced onto the Stormpiercer does not stick',
      JSON.stringify((bowAfter && bowAfter.enchants) || []));

  const shootAt = archer.players['Dummy'] && archer.players['Dummy'].entity;
  if (!shootAt) {
    fail('Archer can see Dummy');
  } else {
    console.log('   partial draw first (must not shock)');
    await archer.lookAt(shootAt.position.offset(0, 1.5, 0), true);
    archer.activateItem();
    await sleep(200);
    archer.deactivateItem();
    await sleep(2500);

    // A full draw at four blocks, up to three attempts: a bot's aim is not what is under
    // test here, and a single miss would fail a run for no reason. Both are put back on
    // their marks and Dummy healed to full before every shot, so the drop is one arrow's.
    // `probe`, if given, runs the moment the hit shows on Dummy's health bar, while the
    // storm_cage is still being counted: the stun it looks at lasts only 40 ticks.
    const fullDrawAtDummy = async (label, arms, probe = null) => {
      for (let attempt = 1; attempt <= 3; attempt++) {
        console.log(`   ${label}, attempt ${attempt}`);
        cmd('tp Dummy 3 -59 0');
        cmd('tp Archer 3 -59 4');
        cmd('effect give Dummy minecraft:instant_health 1 4 true');
        await sleep(1200);
        const target = archer.players['Dummy'] && archer.players['Dummy'].entity;
        if (!target) break;
        await archer.lookAt(target.position.offset(0, 1.5, 0), true);
        archer.actionBars.length = 0;
        // "Stunned" reaches Dummy before the health packet of the hit does, so the bar is
        // cleared here and not once the hit shows.
        dummy.actionBars.length = 0;
        const before = dummy.health;
        archer.activateItem();
        await sleep(1400);
        const fxAt = Date.now();
        archer.deactivateItem();
        if (arms) fullDraws++;
        // The storm_cage (4 end rods + glass) stands for 25 ticks around the victim, so
        // the server-side look at its block states has to happen the moment it appears.
        const endRodsDone = (async () => {
          if (arms && await waitUntil(() => dummy.fxSpawns.some((s) => s.t >= fxAt), 1500)) {
            return fxCount(',nbt={block_state:{Name:"minecraft:end_rod"}}');
          }
          return null;
        })();
        let probed = null;
        if (arms && probe && await waitUntil(() => dummy.health < before, 1500)) {
          probed = await probe();
        }
        const endRods = await endRodsDone;
        await sleep(Math.max(0, 2500 - (Date.now() - fxAt)));
        // The stun probe lands one empty-hand hit of its own; that is not the arrow's.
        const drop = before - dummy.health - ((probed && probed.meleeDrop) || 0);
        const fx = fxSince(dummy, fxAt, 1500, 2000);
        console.log(`   Dummy ${before} -> ${dummy.health} (-${drop.toFixed(1)}); Archer bar `
            + JSON.stringify(archer.actionBars));
        console.log(`   storm_cage: Dummy was sent ${fx.spawned} block displays, ${fx.gone} removed within 2s; `
            + `end_rod displays on the server while it stood: ${endRods}`);
        if (drop > 0) return { drop, bars: archer.actionBars.slice(), fx, endRods, probed };
      }
      return null;
    };

    // The stun: for 40 ticks after the shock Dummy is told "Stunned", carries Slowness VII,
    // and cannot leave the spot it was hit on - the server puts it back every tick, which a
    // vanilla client sees as `position` packets. Once it ends, walking works again.
    // Distances are measured from a sample taken ~3 ticks after the hit, so the arrow's
    // knockback and the /tp before the shot are not counted against it.
    const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
    const stunProbe = async () => {
      const hitAt = Date.now();
      await sleep(150);
      const pinned = dummy.entity.position.clone();
      const slowId = MC_DATA.effectsByName.Slowness.id;
      const result = { pinned, forcedMoves: 0, maxDrift: NaN, endDrift: NaN, serverPinned: NaN, walked: NaN,
        meleeDrop: 0, meleeKick: NaN };
      // For the knockback probe at the end of the stun: Smith, empty-handed (an Aegis Hammer
      // hit would kill a Dummy on 4 hp), two blocks behind Dummy on the Archer side. Put
      // there now so that the hit itself is a single packet later.
      cmd('clear Smith');
      cmd('tp Smith 3 -59 2');

      const told = await waitUntil(() => dummy.actionBars.some((m) => m.trim() === 'Stunned'), 500);
      check(told, 'Dummy is told "Stunned" on its action bar',
          JSON.stringify(dummy.actionBars.slice(-3)));
      const effects = dummy.entity.effects || {};
      const slow = effects[slowId];
      if (Object.keys(effects).length === 0) {
        console.log('   (skip) mineflayer reported no effects on Dummy at all; slowness not checked');
      } else {
        check(slow && slow.amplifier === 6, 'Dummy carries Slowness VII during the stun',
            'effects on the client: ' + JSON.stringify(effects));
      }

      // Try to walk away from Archer (who stands at +z) for 1.5 s, sampling every 50 ms.
      await dummy.lookAt(pinned.offset(0, 1.6, -10), true);
      const movesBefore = dummy.forcedMoves;
      const walkStart = Date.now();
      let maxDrift = 0;
      let serverPinned = null;
      dummy.setControlState('forward', true);
      while (Date.now() - walkStart < 1500) {
        await sleep(50);
        maxDrift = Math.max(maxDrift, dist(dummy.entity.position, pinned));
        // Ask the server itself where Dummy is, once, while the stun is still on.
        if (serverPinned === null && Date.now() - walkStart >= 900) {
          serverPinned = fxCount('',
              `name=Dummy,x=${pinned.x.toFixed(2)},y=${pinned.y.toFixed(2)},z=${pinned.z.toFixed(2)},distance=..0.5`);
        }
      }
      dummy.setControlState('forward', false);
      const endDrift = dist(dummy.entity.position, pinned);
      console.log(`   pinned at ${pinned}, now at ${dummy.entity.position}`);
      result.maxDrift = maxDrift;
      result.endDrift = endDrift;
      result.forcedMoves = dummy.forcedMoves - movesBefore;
      result.serverPinned = serverPinned === null ? NaN : await serverPinned;
      console.log(`   stunned walk attempt: max drift ${maxDrift.toFixed(3)}, end drift ${endDrift.toFixed(3)} blocks; `
          + `${result.forcedMoves} position packets from the server; server sees Dummy within 0.5: ${result.serverPinned}`);
      check(maxDrift <= 0.5 && endDrift <= 0.5,
          'while stunned Dummy cannot walk away (stays within 0.5 of where it was hit)',
          `max ${maxDrift.toFixed(3)}, end ${endDrift.toFixed(3)}`);
      check(result.serverPinned === 1,
          'the server also has Dummy within 0.5 of the spot mid-stun', `count ${result.serverPinned}`);

      // The shock's stun is not rigid: a melee hit on the stunned Dummy still carries its
      // knockback. The server sends a stunned player its (zeroed) motion every tick and
      // snaps it back the tick after, so the position proves nothing either way; what the
      // vanilla client receives for the hit is a non-zero entity_velocity. Done after the
      // walk so the knockback cannot count against the drift measured above.
      const smithSeesDummy = smith.players['Dummy'] && smith.players['Dummy'].entity;
      if (smithSeesDummy) {
        await smith.lookAt(smithSeesDummy.position.offset(0, 1.2, 0), true);
        dummy.velocityPackets.length = 0;
        const meleeBefore = dummy.health;
        const meleeSentAt = Date.now() - hitAt;
        smith.attack(smithSeesDummy);
        const meleeLanded = await waitUntil(() => dummy.health < meleeBefore, 400);
        await sleep(150);
        const kicks = dummy.velocityPackets.map((p) => Math.hypot(p.velocity.x, p.velocity.y, p.velocity.z));
        result.meleeKick = kicks.length ? Math.max(...kicks) : 0;
        result.meleeDrop = meleeBefore - dummy.health;
        console.log(`   empty-hand hit on the stunned Dummy ${meleeSentAt} ms after the shock`
            + ` (${meleeSentAt < 1900 ? 'inside' : 'AFTER'} the 2 s stun): Dummy ${meleeBefore} -> ${dummy.health};`
            + ` ${kicks.length} velocity packets, strongest ${result.meleeKick.toFixed(3)} blocks/tick`
            + ` ${JSON.stringify(dummy.velocityPackets.map((p) => p.velocity).slice(-3))}`);
        check(meleeLanded, 'the melee hit on the stunned Dummy lands', `${meleeBefore} -> ${dummy.health}`);
        check(result.meleeKick > 0.1,
            'the shock stun is not rigid: a melee hit on the stunned Dummy still sends knockback (velocity > 0.1)',
            `${result.meleeKick.toFixed(3)} blocks/tick`);
      } else {
        fail('Smith can see Dummy for the knockback probe');
      }
      // Back out of chain range before any further shot.
      cmd('tp Smith 0 -59 -12');

      // 40 ticks = 2 s. At 2.5 s the effect and the pin are both gone.
      await sleep(Math.max(0, hitAt + 2500 - Date.now()));
      const stillSlow = (dummy.entity.effects || {})[slowId];
      console.log(`   after the stun: slowness on the client: ${JSON.stringify(stillSlow || null)}`);
      const released = dummy.entity.position.clone();
      dummy.setControlState('forward', true);
      await sleep(1000);
      dummy.setControlState('forward', false);
      result.walked = dist(dummy.entity.position, released);
      check(result.walked > 0.8, 'once the stun ends Dummy can walk again (1 s forward > 0.8 blocks)',
          `${result.walked.toFixed(2)} blocks`);
      return result;
    };

    // Dummy wears no armour and has no effects, so the arrow is worth exactly 10 and the
    // shock exactly 6 on top. The arrow loses a whisker of speed over four blocks, hence
    // the tolerance.
    const shocked = await fullDrawAtDummy('full draw, shock ready', true, stunProbe);
    check(!!shocked, 'a fully drawn arrow lands and shocks');
    check(!!(shocked && shocked.probed), 'the stun was observed on the shock hit');
    if (shocked) {
      shockHitAt = Date.now();
      check(Math.abs(shocked.drop - 16) <= 1.5,
          'with the shock ready a full draw deals 10 (arrow) + 6 (shock)',
          `${shocked.drop.toFixed(1)} hp`);
      check(shocked.bars.some((m) => /^Shock/.test(m.trim())),
          'the shooter sees the shock land', shocked.bars.slice(-1)[0] || '');
      check(shocked.fx.spawned >= 5, 'the shock spawns the storm_cage displays (>= 5)', `${shocked.fx.spawned}`);
      check(shocked.endRods >= 1, 'the storm_cage has an end_rod display while it stands',
          `${shocked.endRods} on the server`);

      // Straight away again, well inside the 30s cooldown: the arrow still does its 10, the
      // shock does not fire, and the shooter is told how long is left.
      const plain = await fullDrawAtDummy('full draw, shock on cooldown', false);
      check(!!plain, 'a full draw inside the cooldown still lands');
      if (plain) {
        check(Math.abs(plain.drop - 10) <= 1.5,
            'inside the cooldown a full draw is the arrow alone: 10',
            `${plain.drop.toFixed(1)} hp`);
        check(plain.bars.some((m) => /^Shock\s+\d+s$/.test(m.trim())),
            'the shooter is shown the shock cooldown instead',
            plain.bars.filter((m) => /Shock/.test(m)).slice(-1)[0] || 'no Shock note');
      }
    }
  }

  // ------------------------------------------------------------ 8. new weapons
  console.log('\n== 8. Frostbrand, Stormpiercer execution, Tidecaller, Hellfire ==');
  const section8Start = Date.now();
  // Resistance III, not IV: at IV the damage is zero, the damage event never fires and no
  // melee ability would trigger. At III a Hellfire blast on the head is survivable.
  cmd('effect give Dummy minecraft:resistance 120 2 true');
  cmd('effect give Archer minecraft:resistance 120 4 true');
  const healDummy = async () => {
    cmd('effect give Dummy minecraft:instant_health 1 4 true');
    await sleep(1200);
  };

  // ---- Frostbrand: three landed hits in quick succession freeze the target solid.
  console.log('   -- Frostbrand');
  cmd('clear Smith');
  cmd('customweapon give Smith frostbrand');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  await healDummy();
  await sleep(800);
  const frostbrand = await equipByName(smith, 'iron_sword');
  console.log('   ' + describe(frostbrand));
  check(/Frostbrand/.test(JSON.stringify(frostbrand)), 'the Frostbrand reaches the client named');
  smith.actionBars.length = 0;
  const frostTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!frostTarget) {
    fail('Smith can see Dummy for the Frostbrand');
  } else {
    // A player has 10 ticks of invulnerability after a hit, so swings 350ms apart would
    // mostly be swallowed; 600ms lands every one and thaws only 24 of each hit's 70 ticks.
    const dummyBeforeFrost = dummy.health;
    const frostFxAt = Date.now();
    let shatterAt = 0;
    let iceCubes = null;
    for (let i = 0; i < 5; i++) {
      smith.attack(frostTarget);
      const swungAt = Date.now();
      // The frost_shatter ice cube stands for 45 ticks; look at its block state on the
      // server the moment the shatter is reported.
      if (!shatterAt && await waitUntil(() => smith.actionBars.some((m) => /Shatter/.test(m)), 600)) {
        shatterAt = Date.now();
        iceCubes = await fxCount(',nbt={block_state:{Name:"minecraft:ice"}}');
      }
      await sleep(Math.max(0, 600 - (Date.now() - swungAt)));
    }
    await sleep(1000);
    console.log(`   Dummy ${dummyBeforeFrost} -> ${dummy.health}`);
    console.log('   ' + JSON.stringify(smith.actionBars.filter((m) => /Frost|Shatter/.test(m))));
    check(smith.actionBars.some((m) => /^Frost\s+\d+%/.test(m.trim())),
        'each hit reports the frost build-up on the action bar');
    check(smith.actionBars.some((m) => /Shatter/.test(m)),
        'the frozen target shatters',
        smith.actionBars.filter((m) => /Shatter/.test(m)).slice(-1)[0] || 'no Shatter seen');
    check(dummy.health < dummyBeforeFrost, 'the Frostbrand hurts',
        `${(dummyBeforeFrost - dummy.health).toFixed(1)} hp`);

    // World effects: frost_hit (3) on every hit, frost_shatter (5, one of them an ice cube)
    // on the shatter, all gone again 45 ticks later.
    const frostFx = fxSince(dummy, frostFxAt, 1000, 1500);
    const shatterFx = shatterAt ? fxSince(dummy, shatterAt - 150, 1000, 3000) : { spawned: 0, gone: 0, total: 0 };
    console.log(`   frost_hit: Dummy was sent ${frostFx.spawned} block displays within 1s of the first swing, `
        + `${frostFx.gone} removed within 1.5s; frost_shatter: ${shatterFx.spawned} around the shatter; `
        + `ice-cube displays on the server just after it: ${iceCubes}`);
    check(frostFx.spawned >= 3, 'a Frostbrand hit spawns the frost_hit displays (>= 3)', `${frostFx.spawned}`);
    check(shatterFx.spawned >= 5, 'the shatter spawns the frost_shatter displays (>= 5)', `${shatterFx.spawned}`);
    check(iceCubes >= 1, 'an ice block display stands on the server while the cube is up', `${iceCubes}`);
    if (shatterAt) await sleep(Math.max(0, shatterAt + 3300 - Date.now()));
    const frostLeft = await fxCount();
    check(frostLeft === 0, 'no tagged displays remain 3s after the shatter', `${frostLeft} on the server`);
  }

  // ---- Frostbrand Ice Beam: right-click freezes the first living thing along the look
  // ray (6.0 magic, 60 ticks held in ice via the shared stun), 8 s cooldown, and a ray
  // into the sky is logged as a miss.
  console.log('   -- Frostbrand Ice Beam');
  // Dummy still carries the Resistance III from the top of the section, which would cut
  // the 6.0 to 2.4: no effects and no armour while the beam's damage is measured, and the
  // resistance goes back on afterwards for the Hellfire blast.
  cmd('effect clear Dummy');
  cmd('clear Dummy');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 6 -59 0');
  await healDummy();
  await sleep(800);
  if (!smith.heldItem || smith.heldItem.name !== 'iron_sword') await equipByName(smith, 'iron_sword');
  const beamTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!beamTarget) {
    fail('Smith can see Dummy for the Ice Beam');
  } else {
    const beamDist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
    const slowId = MC_DATA.effectsByName.Slowness.id;
    console.log(`   Smith at ${smith.entity.position}, Dummy at ${dummy.entity.position} `
        + `(${beamDist(smith.entity.position, dummy.entity.position).toFixed(1)} blocks), ${dummy.health} hp, `
        + `effects ${JSON.stringify(dummy.entity.effects || {})}`);
    await smith.lookAt(beamTarget.position.offset(0, 1.2, 0), true);
    await sleep(300);
    smith.actionBars.length = 0;
    dummy.actionBars.length = 0;
    const beamBefore = dummy.health;
    const beamFxAt = Date.now();
    smith.activateItem();
    const beamLanded = await waitUntil(() => dummy.health < beamBefore, 1000);
    const beamHitAt = Date.now();
    check(beamLanded, 'the ice beam hits Dummy within 1 s (health drops)');
    // Where Dummy stands ~3 ticks after the hit is the spot the stun holds it on.
    await sleep(150);
    const frozenAt = dummy.entity.position.clone();
    const toldFrozen = await waitUntil(() => dummy.actionBars.some((m) => m.trim() === 'Frozen'), 500);
    check(toldFrozen, 'Dummy is told "Frozen" on its action bar', JSON.stringify(dummy.actionBars.slice(-3)));
    const beamSlow = (dummy.entity.effects || {})[slowId];
    if (Object.keys(dummy.entity.effects || {}).length === 0) {
      console.log('   (skip) mineflayer reported no effects on Dummy; slowness not checked');
    } else {
      check(beamSlow && beamSlow.amplifier === 6, 'Dummy carries Slowness VII while frozen',
          'effects on the client: ' + JSON.stringify(dummy.entity.effects));
    }
    await waitUntil(() => smith.actionBars.some((m) => /Ice Beam/.test(m)), 700);
    const beamDrop = beamBefore - dummy.health;
    console.log(`   Dummy ${beamBefore} -> ${dummy.health} (-${beamDrop.toFixed(1)}); Smith bar `
        + JSON.stringify(smith.actionBars) + '; Dummy bar ' + JSON.stringify(dummy.actionBars));
    check(Math.abs(beamDrop - 6) <= 1.5, 'the ice beam deals 6.0 (no armour, no effects)', `${beamDrop.toFixed(1)} hp`);
    check(smith.actionBars.some((m) => /Ice Beam/.test(m)), 'Smith sees "Ice Beam" on the action bar',
        smith.actionBars.slice(-1)[0] || '');
    const beamFx = fxSince(dummy, beamFxAt, 1000, 2000);
    const beamFxSmith = fxSince(smith, beamFxAt, 1000, 2000);
    console.log(`   frost_beam: Dummy was sent ${beamFx.spawned} block displays within 1s (Smith ${beamFxSmith.spawned}), `
        + `${beamFx.gone} of them removed within 2s`);
    check(beamFx.spawned >= 5, 'the beam spawns the frost_beam displays (>= 5)', `${beamFx.spawned}`);

    // Held fast: try to walk away from Smith (who stands at -x) for 2.5 s of the 3 s freeze.
    await dummy.lookAt(frozenAt.offset(10, 1.6, 0), true);
    const movesBefore = dummy.forcedMoves;
    const walkStart = Date.now();
    let beamMaxDrift = 0;
    let serverPinned = null;
    dummy.setControlState('forward', true);
    while (Date.now() - walkStart < 2500) {
      await sleep(50);
      beamMaxDrift = Math.max(beamMaxDrift, beamDist(dummy.entity.position, frozenAt));
      if (serverPinned === null && Date.now() - walkStart >= 1500) {
        serverPinned = fxCount('',
            `name=Dummy,x=${frozenAt.x.toFixed(2)},y=${frozenAt.y.toFixed(2)},z=${frozenAt.z.toFixed(2)},distance=..0.5`);
      }
    }
    dummy.setControlState('forward', false);
    const beamEndDrift = beamDist(dummy.entity.position, frozenAt);
    const pinnedOnServer = serverPinned === null ? NaN : await serverPinned;
    console.log(`   frozen at ${frozenAt}, now at ${dummy.entity.position}; max drift ${beamMaxDrift.toFixed(3)}, `
        + `end drift ${beamEndDrift.toFixed(3)}; ${dummy.forcedMoves - movesBefore} position packets from the server; `
        + `server sees Dummy within 0.5: ${pinnedOnServer}`);
    check(beamMaxDrift <= 0.5 && beamEndDrift <= 0.5,
        'while frozen Dummy cannot walk away for 2.5 s (stays within 0.5 of the spot)',
        `max ${beamMaxDrift.toFixed(3)}, end ${beamEndDrift.toFixed(3)}`);
    check(pinnedOnServer === 1, 'the server also has Dummy within 0.5 of the spot mid-freeze', `count ${pinnedOnServer}`);

    // 60 ticks = 3 s. At 3.5 s the ice is gone and walking works again.
    await sleep(Math.max(0, beamHitAt + 3500 - Date.now()));
    const thawSlow = (dummy.entity.effects || {})[slowId];
    console.log(`   after the freeze: slowness on the client: ${JSON.stringify(thawSlow || null)}`);
    const thawedAt = dummy.entity.position.clone();
    dummy.setControlState('forward', true);
    await sleep(1000);
    dummy.setControlState('forward', false);
    const thawWalked = beamDist(dummy.entity.position, thawedAt);
    check(thawWalked > 0.8, 'once the freeze ends Dummy can walk again (1 s forward > 0.8 blocks)',
        `${thawWalked.toFixed(2)} blocks`);

    // A second beam straight away, well inside the 8 s cooldown: refused with the time left.
    smith.actionBars.length = 0;
    const cooldownBefore = dummy.health;
    smith.activateItem();
    await sleep(800);
    console.log('   second use inside the cooldown: Smith bar ' + JSON.stringify(smith.actionBars)
        + `; Dummy ${cooldownBefore} -> ${dummy.health}`);
    check(smith.actionBars.some((m) => /^Ice Beam\s+[\d.]+s$/.test(m.trim())),
        'a second beam on cooldown is refused with the time left',
        smith.actionBars.filter((m) => /Ice Beam/.test(m)).slice(-1)[0] || 'no Ice Beam note');
    check(dummy.health === cooldownBefore, 'the refused beam does no damage', `${cooldownBefore} -> ${dummy.health}`);

    // A miss: once the cooldown is over, fire into the sky (60 degrees up). The server logs
    // it as victim=miss and Dummy is untouched.
    await sleep(Math.max(0, beamHitAt + 8500 - Date.now()));
    cmd('tp Dummy 6 -59 0');
    await sleep(600);
    await smith.look(smith.entity.yaw, Math.PI / 3, true);   // mineflayer pitch +60 deg = notchian -60, up
    await sleep(300);
    const logOffset = fs.readFileSync(RUN + '/test.log', 'utf8').length;
    smith.actionBars.length = 0;
    const missBefore = dummy.health;
    const missFiredAt = Date.now();
    smith.activateItem();
    const missLogged = await waitUntil(() =>
        /ABILITY frostbeam player=Smith victim=miss/.test(fs.readFileSync(RUN + '/test.log', 'utf8').slice(logOffset)), 1500);
    await sleep(500);
    const missTail = fs.readFileSync(RUN + '/test.log', 'utf8').slice(logOffset).split('\n')
        .filter((l) => /ABILITY frostbeam/.test(l)).map((l) => l.replace(/^.*?(ABILITY)/, '$1'));
    console.log(`   into the sky: log ${JSON.stringify(missTail)}; Smith bar ${JSON.stringify(smith.actionBars)}; `
        + `Dummy ${missBefore} -> ${dummy.health}`);
    check(missLogged, 'a beam into the sky is logged as victim=miss', JSON.stringify(missTail));
    check(dummy.health === missBefore, 'the missed beam changes nothing on Dummy', `${missBefore} -> ${dummy.health}`);
    check(smith.actionBars.some((m) => m.trim() === 'Ice Beam'), 'a missed beam still reports "Ice Beam"',
        JSON.stringify(smith.actionBars));

    // ---- Rigid freeze: a target held by the Ice Beam takes no knockback at all, not even
    // from the killing blow (KnockbackMixin cancels it while Stuns.isRigid). Section 7 is
    // the contrast: the shock's stun still lets a hit kick.
    console.log('   -- Ice Beam rigid freeze');
    const kickOf = (bot) => {
      const k = bot.velocityPackets.map((p) => Math.hypot(p.velocity.x, p.velocity.y, p.velocity.z));
      return k.length ? Math.max(...k) : 0;
    };
    // (a) Dummy, beamed from 6 blocks and then hit twice with the Frostbrand inside the 3 s
    // freeze. Resistance III keeps two sword hits and a possible shatter survivable;
    // knockback pays no attention to resistance. Both views are measured: Dummy's own
    // client (which is what would apply a knockback velocity) and Dummy as Smith sees it.
    cmd('effect give Dummy minecraft:resistance 120 2 true');
    cmd('tp Smith 0 -59 0');
    cmd('tp Dummy 6 -59 0');
    await healDummy();
    await sleep(Math.max(0, missFiredAt + 8600 - Date.now()));   // the sky shot's 8 s cooldown
    const rigidTarget = (smith.players['Dummy'] && smith.players['Dummy'].entity) || beamTarget;
    await smith.lookAt(rigidTarget.position.offset(0, 1.2, 0), true);
    await sleep(300);
    dummy.actionBars.length = 0;
    smith.actionBars.length = 0;
    const rigidBefore = dummy.health;
    smith.activateItem();
    const rigidLanded = await waitUntil(() => dummy.health < rigidBefore, 1000);
    const rigidHitAt = Date.now();
    check(rigidLanded, 'the ice beam hits Dummy again for the rigid-freeze probe', `${rigidBefore} -> ${dummy.health}`);
    await sleep(150);
    const rigidAt = dummy.entity.position.clone();
    const rigidSeen = rigidTarget.position.clone();
    // Smith steps in to two blocks and swings twice, 600 ms apart, ~0.7 s and ~1.3 s into
    // the freeze, sampling both positions every 25 ms until ~2.2 s.
    cmd('tp Smith 4 -59 0');
    await sleep(500);
    await smith.lookAt(rigidTarget.position.offset(0, 1.2, 0), true);
    dummy.velocityPackets.length = 0;
    const rigidMovesBefore = dummy.forcedMoves;
    const rigidStart = Date.now();
    const rigidSwings = [];
    let rigidMaxOwn = 0;
    let rigidMaxSeen = 0;
    while (Date.now() - rigidStart < 1600) {
      const t = Date.now() - rigidStart;
      if (rigidSwings.length < 2 && t >= rigidSwings.length * 600) {
        rigidSwings.push({ t, health: dummy.health });
        smith.attack(rigidTarget);
      }
      await sleep(25);
      rigidMaxOwn = Math.max(rigidMaxOwn, beamDist(dummy.entity.position, rigidAt));
      rigidMaxSeen = Math.max(rigidMaxSeen, beamDist(rigidTarget.position, rigidSeen));
    }
    const rigidHealthAfter = dummy.health;
    const rigidDrops = rigidSwings.map((s, i) => s.health - (i + 1 < rigidSwings.length ? rigidSwings[i + 1].health : rigidHealthAfter));
    const rigidKick = kickOf(dummy);
    console.log(`   frozen at ${rigidAt} (Smith sees ${rigidSeen}); two Frostbrand hits while frozen: `
        + `health drops ${JSON.stringify(rigidDrops.map((d) => +d.toFixed(1)))}; `
        + `max displacement own ${rigidMaxOwn.toFixed(3)}, seen by Smith ${rigidMaxSeen.toFixed(3)}; `
        + `${dummy.forcedMoves - rigidMovesBefore} position packets; `
        + `${dummy.velocityPackets.length} velocity packets, strongest ${rigidKick.toFixed(3)} blocks/tick, `
        + `${(Date.now() - rigidHitAt) / 1000}s into the freeze`);
    check(rigidDrops.length === 2 && rigidDrops.every((d) => d > 0),
        'both Frostbrand hits on the frozen Dummy land', JSON.stringify(rigidDrops));
    check(rigidMaxOwn <= 0.15 && rigidMaxSeen <= 0.15,
        'a frozen Dummy takes no knockback: it stays within 0.15 of the frozen spot through two hits',
        `own ${rigidMaxOwn.toFixed(3)}, seen ${rigidMaxSeen.toFixed(3)}`);
    check(rigidKick <= 0.05,
        'no knockback velocity reaches the frozen Dummy (every velocity packet is zero)',
        `strongest ${rigidKick.toFixed(3)} blocks/tick over ${dummy.velocityPackets.length} packets`);

    // Control: once the ice is gone (60 ticks) the same hit kicks Dummy away.
    await sleep(Math.max(0, rigidHitAt + 3500 - Date.now()));
    await smith.lookAt(rigidTarget.position.offset(0, 1.2, 0), true);
    dummy.velocityPackets.length = 0;
    const ctrlAt = dummy.entity.position.clone();
    const ctrlSeen = rigidTarget.position.clone();
    const ctrlBefore = dummy.health;
    const ctrlStart = Date.now();
    smith.attack(rigidTarget);
    let ctrlMaxOwn = 0;
    let ctrlMaxSeen = 0;
    while (Date.now() - ctrlStart < 1000) {
      await sleep(25);
      ctrlMaxOwn = Math.max(ctrlMaxOwn, beamDist(dummy.entity.position, ctrlAt));
      ctrlMaxSeen = Math.max(ctrlMaxSeen, beamDist(rigidTarget.position, ctrlSeen));
    }
    const ctrlKick = kickOf(dummy);
    console.log(`   control hit after the thaw: Dummy ${ctrlBefore} -> ${dummy.health}; `
        + `max displacement own ${ctrlMaxOwn.toFixed(3)}, seen by Smith ${ctrlMaxSeen.toFixed(3)}; `
        + `${dummy.velocityPackets.length} velocity packets, strongest ${ctrlKick.toFixed(3)} blocks/tick`);
    check(dummy.health < ctrlBefore, 'the control hit after the thaw lands', `${ctrlBefore} -> ${dummy.health}`);
    check(ctrlMaxOwn > 0.3 || ctrlKick > 0.1,
        'control: once the ice is gone the same hit knocks Dummy back (> 0.3 blocks or a velocity packet)',
        `moved ${ctrlMaxOwn.toFixed(3)}, velocity ${ctrlKick.toFixed(3)}`);

    // (b) A mob: a husk frozen by the beam and killed with one Frostbrand hit dies where it
    // stood - the killing blow's knockback is cancelled too, so the body does not fly.
    // 12 hp: the beam's 6.0 (magic, past the husk's 2 armour) leaves 6, one sword hit (8,
    // 7.4 past the armour) ends it. Not NoAI: a NoAI mob never runs its travel step, so it
    // neither falls nor slides from a knockback and would pass this for the wrong reason.
    // Instead its movement speed is 0: it wants Smith, cannot take a step, and a knockback
    // still carries it (the control below shows that).
    cmd('tp Dummy 12 -59 12');
    cmd('tp Smith 0 -59 0');
    await sleep(Math.max(0, rigidHitAt + 8600 - Date.now()));
    const huskSeen = (not) => Object.values(smith.entities).find((e) => e.name === 'husk' && e !== not);
    // mineflayer's 1.21.11 tables never move a mob on the client (a summoned husk stays on
    // its summon coordinate for good), so the husk is watched on the server: `data get
    // entity <uuid> Pos` echoes "Husk has the following entity data: [x, y, z]" into
    // test.log. By UUID, not @e: a selector skips a dying mob, and the corpse is the point.
    // null once the server no longer has it (removed).
    const HUSK_UUID = ['00000000-0000-0001-0000-000000000002', '00000000-0000-0001-0000-000000000003'];
    const HUSK_NBT = ['UUID:[I;0,1,0,2]', 'UUID:[I;0,1,0,3]'].map((u) =>
        `Silent:1b,PersistenceRequired:1b,attributes:[{id:"minecraft:movement_speed",base:0.0}],${u}`);
    const huskPosOnServer = async (uuid) => {
      const offset = fs.readFileSync(RUN + '/test.log', 'utf8').length;
      cmd(`data get entity ${uuid} Pos`);
      for (let i = 0; i < 10; i++) {
        await sleep(50);
        const tail = fs.readFileSync(RUN + '/test.log', 'utf8').slice(offset);
        const m = tail.match(/Husk has the following entity data: \[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/);
        if (m) return { x: +m[1], y: +m[2], z: +m[3] };
        if (/No entity was found/.test(tail)) return null;
      }
      return null;
    };
    const fmt = (p) => p ? `(${p.x.toFixed(3)}, ${p.y.toFixed(3)}, ${p.z.toFixed(3)})` : 'none';
    const huskDeath = async (label, husk, uuid, stood) => {
      let goneAt = 0;
      const onGone = (e) => { if (e.id === husk.id) goneAt = Date.now(); };
      smith.on('entityGone', onGone);
      await smith.lookAt(husk.position.offset(0, 0.5, 0), true);
      const hitAt = Date.now();
      const hitFrom = smith.entity.position.clone();
      smith.attack(husk);
      // Server positions every ~60 ms until the server no longer has a husk (or 2.5 s).
      const trace = [];
      while (Date.now() - hitAt < 2500) {
        const p = await huskPosOnServer(uuid);
        if (!p) break;
        trace.push({ t: Date.now() - hitAt, p, d: beamDist(p, stood) });
      }
      await waitUntil(() => goneAt > 0, 1500);
      smith.removeListener('entityGone', onGone);
      const maxDrift = trace.length ? Math.max(...trace.map((s) => s.d)) : NaN;
      const last = trace.length ? trace[trace.length - 1] : null;
      const drift = last ? last.d : NaN;
      console.log(`   ${label}: hit from ${hitFrom} (${beamDist(hitFrom, stood).toFixed(2)} blocks); stood at ${fmt(stood)}, `
          + `last on the server at ${fmt(last && last.p)} (${last ? last.t : '-'} ms): displacement ${drift.toFixed(3)}, `
          + `max ${maxDrift.toFixed(3)} over ${trace.length} samples; client saw it removed `
          + `${goneAt ? ((goneAt - hitAt) / 1000).toFixed(2) + 's' : 'NOT'} after the hit`);
      console.log('   server trace (ms: displacement): ' + trace.map((s) => `${s.t}:${s.d.toFixed(2)}`).join(' '));
      return { goneAt, drift, maxDrift, samples: trace.length };
    };
    cmd(`summon minecraft:husk 5.5 -59 0.5 {Health:12f,${HUSK_NBT[0]}}`);
    await sleep(1000);
    const husk = huskSeen(null);
    check(!!husk, 'a husk stands 5 blocks ahead of Smith');
    let huskBeamAt = Date.now();
    if (husk) {
      const huskStood = await huskPosOnServer(HUSK_UUID[0]);
      await smith.lookAt(husk.position.offset(0, 1.0, 0), true);
      await sleep(300);
      const huskLogOffset = fs.readFileSync(RUN + '/test.log', 'utf8').length;
      smith.actionBars.length = 0;
      huskBeamAt = Date.now();
      smith.activateItem();
      const huskFrozen = await waitUntil(() =>
          /ABILITY frostbeam player=Smith victim=Husk/.test(fs.readFileSync(RUN + '/test.log', 'utf8').slice(huskLogOffset)), 1500);
      check(huskFrozen, 'the ice beam freezes the husk (logged victim=Husk)', JSON.stringify(smith.actionBars));
      await sleep(150);
      const huskFrozenAt = await huskPosOnServer(HUSK_UUID[0]);
      console.log(`   husk stood at ${fmt(huskStood)}, frozen at ${fmt(huskFrozenAt)}`
          + ` (${huskStood && huskFrozenAt ? beamDist(huskStood, huskFrozenAt).toFixed(3) : '?'} apart)`);
      check(!!huskFrozenAt, 'the server still has the husk after the beam');
      cmd('tp Smith 3 -59 0');
      await sleep(500);
      const frozenKill = await huskDeath('frozen husk, one Frostbrand hit', husk, HUSK_UUID[0], huskFrozenAt || huskStood);
      check(frozenKill.goneAt > 0, 'one Frostbrand hit kills the frozen husk (gone within 2.5 s)');
      check(frozenKill.samples > 0 && frozenKill.drift <= 0.3 && frozenKill.maxDrift <= 0.3,
          'the frozen husk dies where it stood: the killing blow moves it by no more than 0.3',
          `last ${frozenKill.drift.toFixed(3)}, max ${frozenKill.maxDrift.toFixed(3)}`);

      // Control: an unfrozen husk killed the same way is knocked off its spot.
      cmd(`summon minecraft:husk 5.5 -59 0.5 {Health:4f,${HUSK_NBT[1]}}`);
      await sleep(1000);
      const husk2 = huskSeen(husk);
      check(!!husk2, 'a second, unfrozen husk stands in the same place');
      if (husk2) {
        const stood2 = await huskPosOnServer(HUSK_UUID[1]);
        check(!!stood2, 'the server has the second husk');
        const plainKill = await huskDeath('unfrozen husk, one Frostbrand hit', husk2, HUSK_UUID[1], stood2 || { x: 5.5, y: -59, z: 0.5 });
        check(plainKill.goneAt > 0, 'one Frostbrand hit kills the unfrozen husk too');
        check(plainKill.maxDrift > 0.3,
            'control: the unfrozen husk is knocked more than 0.3 by the killing blow',
            `max ${plainKill.maxDrift.toFixed(3)}`);
      }
    }

    // ---- The jar's default: frost_beam_cooldown_ticks is 600 (30 s). The harness config
    // pins it to 160 so the timings above hold; drop the key, reload, and a freshly given
    // Frostbrand must describe a 30 s cooldown in its lore, and a second use back to back
    // must be refused with 20+ s left. Then the key goes back.
    console.log('   -- Ice Beam default cooldown (config key removed, jar default)');
    const cfgPath = RUN + '/config/customweapons.json';
    const cfgWithKey = fs.readFileSync(cfgPath, 'utf8');
    check(/"frost_beam_cooldown_ticks": 160,/.test(cfgWithKey), 'the harness config pins frost_beam_cooldown_ticks to 160');
    fs.writeFileSync(cfgPath, cfgWithKey.replace(/\n\s*"frost_beam_cooldown_ticks": 160,/, ''));
    cmd('customweapon reload');
    await sleep(2000);
    cmd('clear Smith');
    cmd('customweapon give Smith frostbrand');
    await sleep(1500);
    const freshFrostbrand = smith.inventory.items().find((i) => i.name === 'iron_sword');
    const freshLore = freshFrostbrand ? JSON.stringify((freshFrostbrand.components || []).find((c) => c.type === 'lore') || null) : 'no sword';
    const loreLines = (freshLore.match(/"value":"[^"]*"/g) || []).map((s) => s.slice(9, -1))
        .filter((s) => /cooldown/i.test(s));
    console.log('   fresh Frostbrand lore lines mentioning a cooldown: ' + JSON.stringify(loreLines));
    check(/cooldown 30s/.test(freshLore),
        'a Frostbrand given on the jar default reads "cooldown 30s" in its Ice Beam lore line',
        loreLines.join(' | ') || freshLore.slice(0, 200));
    if (freshFrostbrand) await equipByName(smith, 'iron_sword');
    await sleep(Math.max(0, huskBeamAt + 8600 - Date.now()));
    await smith.look(smith.entity.yaw, Math.PI / 3, true);
    await sleep(300);
    smith.actionBars.length = 0;
    smith.activateItem();
    await sleep(700);
    smith.activateItem();
    await sleep(800);
    const defaultNote = smith.actionBars.map((m) => m.trim()).find((m) => /^Ice Beam\s+[\d.]+s$/.test(m));
    const defaultLeft = defaultNote ? parseFloat(defaultNote.replace(/^Ice Beam\s+/, '')) : NaN;
    console.log(`   two uses back to back on the default: Smith bar ${JSON.stringify(smith.actionBars)}`);
    check(defaultLeft >= 20,
        'on the jar default a second beam is refused with 20+ s left (600 ticks = 30 s)',
        defaultNote || 'no "Ice Beam  Ns" note');
    fs.writeFileSync(cfgPath, cfgWithKey);
    cmd('customweapon reload');
    await sleep(2000);
  }
  // Resistance III back on for the rest of the section (the Hellfire blast on the head).
  cmd('effect give Dummy minecraft:resistance 120 2 true');
  await healDummy();

  // ---- Stormpiercer vs a creeper: a fully drawn hit executes it outright.
  console.log('   -- Stormpiercer execution');
  cmd('clear Archer');
  cmd('customweapon give Archer stormpiercer');
  cmd('give Archer minecraft:arrow 16');
  cmd('tp Dummy 12 -59 12');          // out of chain range, so the chain cannot hit it
  cmd('tp Archer 3 -59 4');
  await sleep(1500);
  await equipByName(archer, 'bow');
  // The shock cooldown (30s) was charged by the hit in section 7, and a full draw inside it
  // is a plain arrow that would not execute anything. Nothing resets it short of a
  // reconnect, so wait it out; the shot then proves the shock comes back on its own.
  const cooldownLeft = shockHitAt ? shockHitAt + 31500 - Date.now() : 0;
  if (cooldownLeft > 0) {
    console.log(`   waiting ${(cooldownLeft / 1000).toFixed(1)}s for the shock cooldown`);
    await sleep(cooldownLeft);
  }

  let executed = false;
  for (let attempt = 1; attempt <= 3 && !executed; attempt++) {
    console.log(`   creeper, attempt ${attempt}`);
    cmd('kill @e[type=minecraft:creeper]');
    cmd('kill @e[type=minecraft:arrow]');
    await sleep(600);
    cmd('summon minecraft:creeper 3 -59 0 {NoAI:1b}');
    cmd('tp Archer 3 -59 4');
    await sleep(1500);
    const creeper = Object.values(archer.entities).find((e) => e.name === 'creeper');
    if (!creeper) {
      fail('Archer can see the creeper');
      break;
    }
    await archer.lookAt(creeper.position.offset(0, 0.9, 0), true);
    archer.activateItem();
    await sleep(1400);
    archer.deactivateItem();
    fullDraws++;
    // A death animation is 20 ticks; the removal packet follows it.
    const shotAt = Date.now();
    while (Date.now() - shotAt < 3000 && archer.entities[creeper.id]) await sleep(100);
    executed = !archer.entities[creeper.id];
    console.log(`   creeper ${executed ? 'is gone' : 'still standing'} after ${Date.now() - shotAt}ms`);
    if (!executed) await sleep(2000);
  }
  check(executed, 'a fully drawn Stormpiercer arrow executes a creeper');
  check(archer.actionBars.some((m) => /executed/.test(m)),
      'the shooter is told it was an execution',
      archer.actionBars.filter((m) => /Shock/.test(m)).slice(-1)[0] || '');

  // ---- Tidecaller: a thrown hit drags the target to the thrower.
  console.log('   -- Tidecaller');
  cmd('kill @e[type=minecraft:creeper]');
  await healDummy();
  let harpooned = false;
  for (let attempt = 1; attempt <= 3 && !harpooned; attempt++) {
    console.log(`   throw, attempt ${attempt}`);
    // A thrown trident that missed is lying on the floor; each attempt gets a fresh one.
    cmd('clear Archer');
    cmd('customweapon give Archer tidecaller');
    cmd('tp Archer 3 -59 4');
    cmd('tp Dummy 3 -59 -2');
    await sleep(1500);
    const trident = await equipByName(archer, 'trident');
    if (attempt === 1) {
      console.log('   ' + describe(trident));
      check(/Tidecaller/.test(JSON.stringify(trident)), 'the Tidecaller reaches the client named');
    }
    const throwAt = archer.players['Dummy'] && archer.players['Dummy'].entity;
    if (!throwAt) {
      fail('Archer can see Dummy for the throw');
      break;
    }
    dummy.velocityPackets.length = 0;
    archer.actionBars.length = 0;
    await archer.lookAt(throwAt.position.offset(0, 1.4, 0), true);
    archer.activateItem();             // a trident throws on release after >= 10 ticks of use
    await sleep(700);
    const throwFxAt = Date.now();
    archer.deactivateItem();
    await sleep(2000);
    const yank = dummy.velocityPackets[dummy.velocityPackets.length - 1];
    const bar = archer.actionBars.filter((m) => /Harpoon/.test(m)).slice(-1)[0];
    console.log(`   Dummy velocity packets: ${dummy.velocityPackets.length}`
        + (yank ? ' ' + JSON.stringify(yank.velocity) : '') + (bar ? `; action bar "${bar}"` : ''));
    harpooned = !!yank || (!!bar && bar.trim() === 'Harpoon');
    if (harpooned) {
      // tide_splash (10) on the victim plus a 12-bead sea_lantern line back to the thrower.
      const fx = fxSince(dummy, throwFxAt, 1500, 1500);
      console.log(`   tide_splash + line: Dummy was sent ${fx.spawned} block displays, ${fx.gone} removed within 1.5s`);
      check(fx.spawned >= 22, 'the harpoon spawns the splash and the bead line (>= 22 displays)', `${fx.spawned}`);
    }
  }
  check(harpooned, 'a thrown Tidecaller hit yanks the target toward the thrower');
  check(archer.actionBars.some((m) => m.trim() === 'Harpoon'),
      'the harpoon reports on the action bar');

  // ---- Hellfire: the first bolt after the cooldown is armed and explodes on impact.
  console.log('   -- Hellfire');
  cmd('kill @e[type=minecraft:trident]');
  cmd('kill @e[type=minecraft:item]');
  cmd('clear Archer');
  cmd('customweapon give Archer hellfire');
  cmd('give Archer minecraft:arrow 16');
  cmd('tp Dummy 3 -59 -2');
  cmd('tp Archer 3 -59 4');
  await healDummy();
  await sleep(500);
  const hellfire = await equipByName(archer, 'crossbow');
  console.log('   ' + describe(hellfire));
  check(/Hellfire/.test(JSON.stringify(hellfire)), 'the Hellfire reaches the client named');

  let blasted = false;
  let boltFxAt = 0;
  for (let attempt = 1; attempt <= 2 && !blasted; attempt++) {
    console.log(`   bolt, attempt ${attempt}`);
    cmd('tp Dummy 3 -59 -2');
    cmd('tp Archer 3 -59 4');
    await sleep(1000);
    const boltAt = archer.players['Dummy'] && archer.players['Dummy'].entity;
    if (!boltAt) {
      fail('Archer can see Dummy for the bolt');
      break;
    }
    archer.actionBars.length = 0;
    dummy.velocityPackets.length = 0;
    const dummyBeforeBolt = dummy.health;
    await archer.lookAt(boltAt.position.offset(0, 1.2, 0), true);
    // A crossbow loads on release after 25 ticks of use, and the next use fires it.
    archer.activateItem();
    await sleep(1500);
    archer.deactivateItem();
    await sleep(400);
    boltFxAt = Date.now();
    archer.activateItem();
    await sleep(2500);
    console.log(`   Dummy ${dummyBeforeBolt} -> ${dummy.health}; velocity packets ${dummy.velocityPackets.length}; `
        + JSON.stringify(archer.actionBars));
    const armed = archer.actionBars.some((m) => m.trim() === 'Hellfire');
    const felt = dummy.health < dummyBeforeBolt || dummy.velocityPackets.length > 0;
    blasted = armed && felt;
    if (blasted) {
      const fx = fxSince(dummy, boltFxAt, 1500, 1500);
      console.log(`   hell_burst: Dummy was sent ${fx.spawned} block displays, ${fx.gone} removed within 1.5s`);
      check(fx.spawned >= 17, 'the explosion spawns the hell_burst displays (>= 17)', `${fx.spawned}`);
    }
    if (!blasted && attempt < 2) {
      await healDummy();
      await sleep(8500 - 1200);        // the next bolt is only armed once the cooldown is over
    }
  }
  check(archer.actionBars.some((m) => m.trim() === 'Hellfire'), 'the armed bolt reports on the action bar');
  check(blasted, 'the Hellfire bolt hurts the target it was fired at');
  if (blasted) {
    // The scorch slab is the longest-lived part, 100 ticks; then nothing may be left.
    await sleep(Math.max(0, boltFxAt + 6500 - Date.now()));
    const hellLeft = await fxCount();
    check(hellLeft === 0, 'no tagged displays remain 6.5s after the explosion', `${hellLeft} on the server`);
  }

  // A bolt that lands on the GROUND next to somebody. This used to kill the server: the
  // ground detonation ran inside the projectile tick loop, its blast damage re-entered the
  // projectile map from the damage event, and the loop's iterator threw a
  // ConcurrentModificationException. The check is simply that the server is still there.
  console.log('   -- Hellfire on the ground');
  cmd('kill @e[type=minecraft:arrow]');
  cmd('tp Archer 0 -59 0');
  cmd('tp Dummy 3 -59 0');
  await healDummy();
  await sleep(Math.max(0, boltFxAt + 8500 - Date.now()));   // the cooldown from the direct hit
  if (!archer.heldItem || archer.heldItem.name !== 'crossbow') await equipByName(archer, 'crossbow');
  archer.actionBars.length = 0;
  dummy.velocityPackets.length = 0;
  const groundBefore = dummy.health;
  // Aim at the floor a block short of Dummy: the bolt lands beside it, never on it.
  await archer.lookAt(dummy.entity.position.offset(-1.0, -1.0, 0), true);
  archer.activateItem();
  await sleep(1500);
  archer.deactivateItem();
  await sleep(400);
  archer.activateItem();
  await sleep(3000);
  cmd('say HELLFIRE_GROUND_ALIVE');
  const groundAliveRe = new RegExp('HELLFIRE_GROUND_ALIVE');
  const alive = await waitUntil(() => smith.chats.some((m) => groundAliveRe.test(m)), 3000);
  console.log(`   ground bolt: Dummy ${groundBefore} -> ${dummy.health}; velocity packets ${dummy.velocityPackets.length}; `
      + `Archer bar ${JSON.stringify(archer.actionBars)}`);
  check(alive, 'the server is still ticking after a Hellfire bolt landed on the ground beside Dummy');
  check(dummy.health < groundBefore || dummy.velocityPackets.length > 0, 'the ground blast still reached Dummy');
  cmd('tp Archer 0 -59 -12');   // out of every later radius: Archer inside the Starfall impact read as a second hit

  // Leave the arena clean for the SMP rules: no stray weapons, no lingering effects.
  cmd('kill @e[type=minecraft:arrow]');
  cmd('kill @e[type=minecraft:item]');
  cmd('clear Archer');
  cmd('effect clear Dummy');
  cmd('effect clear Archer');
  await healDummy();
  console.log(`   section 8 took ${((Date.now() - section8Start) / 1000).toFixed(1)}s`);
  console.log(`   full draws fired with the shock ready: ${fullDraws}`);

  // ------------------------------------- 8b. the v1.4 weapons and abilities
  console.log('\n== 8b. Dawnbreaker, Voidreaper, Starfall, Exsanguinate, Stagger ==');
  const dist3 = (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
  const barHas = (bot, re) => bot.actionBars.some((m) => re.test(m.trim()));
  // The flat world's random seed lands on a slime chunk now and then, and slimes kept
  // appearing mid-section despite spawn_mobs false (one run had a Sunstrike hit four of
  // them and a slime kill both bots). Clear them before every block here.
  const clearSlimes = () => cmd('kill @e[type=minecraft:slime]');
  clearSlimes();
  cmd('effect clear Dummy');
  cmd('effect clear Smith');
  cmd('clear Dummy');
  cmd('clear Smith');
  cmd('time set day');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  await healDummy();

  // ---- Dawnbreaker: a hit burns and, by day, lands +2.0 on top; the Sunstrike falls a
  // second after the mark on whatever the wielder looked at.
  console.log('   -- Dawnbreaker');
  cmd('customweapon give Smith dawnbreaker');
  await sleep(800);
  const dawnbreaker = await equipByName(smith, 'golden_sword');
  console.log('   ' + describe(dawnbreaker));
  check(/Dawnbreaker/.test(JSON.stringify(dawnbreaker)), 'the Dawnbreaker reaches the client named');
  {
    // mineflayer's 1.21.11 component tables mis-read half the ids, so ask the server:
    // `data get` echoes the component into test.log, or "Found no elements" if it is absent.
    const offset = fs.readFileSync(RUN + '/test.log', 'utf8').length;
    cmd('data get entity Smith SelectedItem.components."minecraft:unbreakable"');
    await sleep(600);
    const tail = fs.readFileSync(RUN + '/test.log', 'utf8').slice(offset);
    check(/Smith has the following entity data: \{\}/.test(tail) && !/Found no elements/.test(tail),
        'the Dawnbreaker is unbreakable (server-side component)', tail.trim().split('\n').slice(-1)[0] || '(no echo)');
  }
  let sunTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!sunTarget) {
    fail('Smith can see Dummy for the Dawnbreaker');
  } else {
    smith.actionBars.length = 0;
    const solarBefore = dummy.health;
    await smith.lookAt(sunTarget.position.offset(0, 1.2, 0), true);
    await sleep(800);
    smith.attack(sunTarget);
    await waitUntil(() => dummy.health < solarBefore, 1000);
    await sleep(400);
    const solarDrop = solarBefore - dummy.health;
    console.log(`   one Dawnbreaker hit: Dummy ${solarBefore} -> ${dummy.health} (-${solarDrop.toFixed(1)}); `
        + `Smith bar ${JSON.stringify(smith.actionBars)}; Dummy on fire flag ${(dummy.entity.metadata || [])[0]}`);
    check(solarDrop >= 10 && solarDrop <= 15, 'a daytime hit lands 9.0 + 2.0 Solar Brand (10-15 with the burn)',
        `${solarDrop.toFixed(1)} hp`);
    check(barHas(smith, /^Solar Brand\s+\+2\.0$/), 'Smith sees "Solar Brand  +2.0"', smith.actionBars.slice(-1)[0] || '');

    // Sunstrike from six blocks: mark, one second, pillar.
    cmd('effect clear Dummy');
    cmd('tp Dummy 6 -59 0');
    await healDummy();
    await sleep(600);
    sunTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
    await smith.lookAt(sunTarget.position.offset(0, 1.0, 0), true);
    await sleep(300);
    smith.actionBars.length = 0;
    const strikeBefore = dummy.health;
    const strikeAt = Date.now();
    smith.activateItem();
    await sleep(400);
    check(barHas(smith, /^Sunstrike$/), 'the mark reports "Sunstrike" at once', JSON.stringify(smith.actionBars));
    check(dummy.health === strikeBefore, 'nothing lands during the one-second telegraph', `${strikeBefore} -> ${dummy.health}`);
    const struck = await waitUntil(() => dummy.health < strikeBefore, 2000);
    const strikeLanded = Date.now() - strikeAt;
    await sleep(500);
    const strikeDrop = strikeBefore - dummy.health;
    console.log(`   Sunstrike: Dummy ${strikeBefore} -> ${dummy.health} (-${strikeDrop.toFixed(1)}) after ${strikeLanded} ms; `
        + `Smith bar ${JSON.stringify(smith.actionBars)}`);
    check(struck && strikeLanded >= 800 && strikeLanded <= 2000, 'the pillar lands about a second after the mark', `${strikeLanded} ms`);
    check(strikeDrop >= 8 && strikeDrop <= 13, 'the Sunstrike deals 9.0 (8-13 with the burn)', `${strikeDrop.toFixed(1)} hp`);
    check(barHas(smith, /^Sunstrike\s+1 hit$/), 'Smith sees "Sunstrike  1 hit"', smith.actionBars.slice(-1)[0] || '');
    const sunFx = fxSince(dummy, strikeAt, 2000, 3500);
    console.log(`   sun_telegraph + sunstrike: Dummy was sent ${sunFx.spawned} block displays, ${sunFx.gone} removed within 3.5s`);
    check(sunFx.spawned >= 8, 'the telegraph and the pillar spawn their displays (>= 8)', `${sunFx.spawned}`);
    smith.actionBars.length = 0;
    smith.activateItem();
    await sleep(600);
    check(barHas(smith, /^Sunstrike\s+[\d.]+s$/), 'a second Sunstrike inside the cooldown is refused with the time left',
        JSON.stringify(smith.actionBars));
  }

  // ---- Voidreaper: the Rift puts the wielder behind the mark, the next hit is a backstab,
  // every hit withers, and a kill feeds the wielder.
  console.log('   -- Voidreaper');
  clearSlimes();
  cmd('clear Smith');
  cmd('customweapon give Smith voidreaper');
  cmd('effect clear Dummy');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 6 -59 0');
  await healDummy();
  await sleep(800);
  const voidreaper = await equipByName(smith, 'netherite_hoe');
  console.log('   ' + describe(voidreaper));
  check(/Voidreaper/.test(JSON.stringify(voidreaper)), 'the Voidreaper reaches the client named');
  let riftTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!riftTarget) {
    fail('Smith can see Dummy for the Rift');
  } else {
    await dummy.lookAt(dummy.entity.position.offset(10, 1.6, 0), true);   // Dummy faces +x; behind is -x
    await smith.lookAt(riftTarget.position.offset(0, 1.2, 0), true);
    await sleep(400);
    smith.actionBars.length = 0;
    const riftFrom = smith.entity.position.clone();
    const movesBefore = smith.forcedMoves;
    const riftAt = Date.now();
    smith.activateItem();
    const moved = await waitUntil(() => smith.forcedMoves > movesBefore, 1000);
    await sleep(400);
    const riftTo = smith.entity.position.clone();
    const gap = dist3(riftTo, dummy.entity.position);
    console.log(`   Rift: Smith ${riftFrom} -> ${riftTo}; ${gap.toFixed(2)} from Dummy at ${dummy.entity.position}; `
        + `Smith bar ${JSON.stringify(smith.actionBars)}`);
    check(moved && dist3(riftFrom, riftTo) > 3, 'the Rift moves Smith (a teleport, > 3 blocks)', `${dist3(riftFrom, riftTo).toFixed(2)}`);
    check(gap <= 2.3, 'Smith lands right next to Dummy (<= 2.3 blocks)', `${gap.toFixed(2)}`);
    check(riftTo.x < dummy.entity.position.x - 0.8, 'and behind it: Dummy faces +x, Smith is on the -x side',
        `Smith x ${riftTo.x.toFixed(2)}, Dummy x ${dummy.entity.position.x.toFixed(2)}`);
    check(barHas(smith, /^Rift\s+behind Dummy$/), 'Smith sees "Rift  behind Dummy"', smith.actionBars.slice(-1)[0] || '');
    const riftFx = fxSince(dummy, riftAt, 1000, 2000);
    console.log(`   rift_open x2: Dummy was sent ${riftFx.spawned} block displays, ${riftFx.gone} removed within 2s`);
    check(riftFx.spawned >= 8, 'both rift tears spawn their displays (>= 8)', `${riftFx.spawned}`);

    // The backstab: one hit inside the two-second window.
    riftTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
    await smith.lookAt(riftTarget.position.offset(0, 1.2, 0), true);
    smith.actionBars.length = 0;
    const stabBefore = dummy.health;
    smith.attack(riftTarget);
    await waitUntil(() => dummy.health < stabBefore, 1000);
    await sleep(500);
    const stabDrop = stabBefore - dummy.health;
    const witherId = MC_DATA.effectsByName.Wither.id;
    const withered = (dummy.entity.effects || {})[witherId];
    console.log(`   backstab: Dummy ${stabBefore} -> ${dummy.health} (-${stabDrop.toFixed(1)}); Smith bar `
        + `${JSON.stringify(smith.actionBars)}; wither on the client: ${JSON.stringify(withered || null)}`);
    check(stabDrop >= 15 && stabDrop <= 20, 'the backstab lands 10.0 + 6.0 (15-20 with the wither tick)', `${stabDrop.toFixed(1)} hp`);
    check(barHas(smith, /^Backstab\s+\+6\.0$/), 'Smith sees "Backstab  +6.0"', smith.actionBars.slice(-1)[0] || '');
    if (Object.keys(dummy.entity.effects || {}).length === 0) {
      console.log('   (skip) mineflayer reported no effects on Dummy; wither not checked');
    } else {
      check(!!withered, 'Dummy carries Wither after the hit', JSON.stringify(dummy.entity.effects));
    }
    smith.actionBars.length = 0;
    const plainBefore = dummy.health;
    await sleep(700);
    smith.attack(riftTarget);
    await waitUntil(() => dummy.health < plainBefore, 1000);
    await sleep(400);
    check(!barHas(smith, /^Backstab/), 'the backstab is spent: the next hit is a plain one', JSON.stringify(smith.actionBars));

    // Soul Harvest: a one-hit kill heals Smith 4.0. Smith is still where the Rift put them,
    // four blocks from where the zombie appears: back to the origin first, or the swing
    // never reaches it.
    clearSlimes();
    cmd('tp Smith 0 -59 0');
    cmd('damage Smith 10 minecraft:generic');
    cmd('summon minecraft:zombie 1 -59 2 {NoAI:1b,Health:1f,Silent:1b}');
    // Up to 4 s for the spawn packet: one run's zombie took longer than a second to appear.
    let zombie = null;
    await waitUntil(() => (zombie = Object.values(smith.entities).find((e) => e.name === 'zombie')), 4000);
    if (!zombie) {
      fail('a zombie was summoned next to Smith for the harvest');
    } else {
      // The /damage above has to have landed before the baseline is read, or the heal is
      // measured against a health the damage then pulls down (one run read 20 -> 14).
      await waitUntil(() => smith.health <= 12, 3000);
      const hpBefore = smith.health;
      smith.actionBars.length = 0;
      console.log(`   zombie at ${zombie.position}, Smith at ${smith.entity.position} `
          + `(${dist3(zombie.position, smith.entity.position).toFixed(2)} blocks), Smith holding ${smith.heldItem ? smith.heldItem.name : 'nothing'}`);
      await smith.lookAt(zombie.position.offset(0, 1.0, 0), true);
      await sleep(300);
      // Two swings 700 ms apart: one run saw the first swing never register (the zombie
      // stood untouched and turned up inside the next weapon's impact radius).
      let fed = false;
      for (let swing = 0; swing < 2 && !fed; swing++) {
        smith.attack(zombie);
        fed = await waitUntil(() => smith.health > hpBefore, 700);
      }
      await sleep(300);
      cmd('data get entity @e[type=minecraft:zombie,limit=1] Health');
      console.log(`   harvest: Smith ${hpBefore} -> ${smith.health}; Smith bar ${JSON.stringify(smith.actionBars)}`);
      check(fed && smith.health - hpBefore >= 3.5, 'the kill heals Smith 4.0 (Soul Harvest)', `${(smith.health - hpBefore).toFixed(1)}`);
      check(barHas(smith, /^Soul Harvest\s+\+4\.0$/), 'Smith sees "Soul Harvest  +4.0"', smith.actionBars.slice(-1)[0] || '');
    }
    cmd('kill @e[type=minecraft:zombie]');
  }

  // ---- Starfall: the Comet launches, and landing while falling as a comet is the impact.
  console.log('   -- Starfall');
  clearSlimes();
  cmd('kill @e[type=minecraft:zombie]');
  cmd('clear Smith');
  cmd('customweapon give Smith starfall');
  cmd('effect clear Dummy');
  cmd('effect clear Smith');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  await healDummy();
  await sleep(800);
  const starfall = await equipByName(smith, 'mace');
  console.log('   ' + describe(starfall));
  check(/Starfall/.test(JSON.stringify(starfall)), 'the Starfall reaches the client named');
  {
    smith.actionBars.length = 0;
    smith.velocityPackets.length = 0;
    const cometAt = Date.now();
    const impactBefore = dummy.health;
    smith.activateItem();
    await sleep(300);
    const launch = smith.velocityPackets[smith.velocityPackets.length - 1];
    console.log(`   Comet: velocity packets ${smith.velocityPackets.length}, last ${JSON.stringify(launch || null)}; `
        + `Smith bar ${JSON.stringify(smith.actionBars)}`);
    check(!!launch && launch.velocity && launch.velocity.y > 0.5, 'the Comet sends an upward velocity (y > 0.5 blocks/tick)', launch ? JSON.stringify(launch.velocity) : 'no packet');
    check(barHas(smith, /^Comet$/), 'Smith sees "Comet"', smith.actionBars.slice(-1)[0] || '');
    // The headless bot ignores the launch, so the fall is a jump: airborne, then down.
    smith.setControlState('jump', true);
    await sleep(150);
    smith.setControlState('jump', false);
    const impacted = await waitUntil(() => dummy.health < impactBefore, 2500);
    await sleep(500);
    const impactDrop = impactBefore - dummy.health;
    console.log(`   Impact: Dummy ${impactBefore} -> ${dummy.health} (-${impactDrop.toFixed(1)}); Smith bar ${JSON.stringify(smith.actionBars)}; `
        + `Dummy velocity packets ${dummy.velocityPackets.length}`);
    check(impacted && impactDrop >= 7 && impactDrop <= 11, 'landing deals the 8.0 impact to Dummy two blocks away (7-11 with the burn)',
        `${impactDrop.toFixed(1)} hp`);
    check(barHas(smith, /^Impact\s+1 hit$/), 'Smith sees "Impact  1 hit"', smith.actionBars.slice(-1)[0] || '');
    const meteorFx = fxSince(dummy, cometAt, 3000, 4000);
    console.log(`   comet_launch + meteor_impact: Dummy was sent ${meteorFx.spawned} block displays, ${meteorFx.gone} removed within 4s`);
    check(meteorFx.spawned >= 12, 'the launch and the impact spawn their displays (>= 12)', `${meteorFx.spawned}`);
    smith.actionBars.length = 0;
    smith.activateItem();
    await sleep(500);
    check(barHas(smith, /^Comet\s+[\d.]+s$/), 'a second Comet inside the cooldown is refused with the time left',
        JSON.stringify(smith.actionBars));
  }

  // ---- Bloodletter Exsanguinate: bursts the bleeds the wielder owns nearby.
  console.log('   -- Exsanguinate');
  clearSlimes();
  cmd('clear Smith');
  cmd('customweapon give Smith bloodletter');
  cmd('effect clear Dummy');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  await healDummy();
  await sleep(800);
  await equipByName(smith, 'netherite_sword');
  const bleedTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!bleedTarget) {
    fail('Smith can see Dummy for the Exsanguinate');
  } else {
    smith.actionBars.length = 0;
    smith.activateItem();
    await sleep(500);
    check(barHas(smith, /^Exsanguinate\s+nothing bleeding$/), 'with nothing bleeding it says so and costs nothing',
        JSON.stringify(smith.actionBars));
    await smith.lookAt(bleedTarget.position.offset(0, 1.2, 0), true);
    for (let i = 0; i < 2; i++) { smith.attack(bleedTarget); await sleep(600); }
    await sleep(200);
    smith.actionBars.length = 0;
    const burstBefore = dummy.health;
    const burstAt = Date.now();
    smith.activateItem();
    const burst = await waitUntil(() => dummy.health < burstBefore - 4, 800);
    await sleep(400);
    const burstDrop = burstBefore - dummy.health;
    console.log(`   Exsanguinate: Dummy ${burstBefore} -> ${dummy.health} (-${burstDrop.toFixed(1)}) ; Smith bar ${JSON.stringify(smith.actionBars)}`);
    check(burst && burstDrop >= 5, 'the burst lands the rest of the bleed at once (>= 5 within 0.8 s)', `${burstDrop.toFixed(1)} hp`);
    check(barHas(smith, /^Exsanguinate\s+1 burst, \+4\.0$/), 'Smith sees "Exsanguinate  1 burst, +4.0" (two stacks)',
        smith.actionBars.slice(-1)[0] || '');
    const burstFx = fxSince(dummy, burstAt, 1000, 2000);
    console.log(`   blood_burst: Dummy was sent ${burstFx.spawned} block displays, ${burstFx.gone} removed within 2s`);
    check(burstFx.spawned >= 4, 'the burst spawns its displays (>= 4)', `${burstFx.spawned}`);
    await sleep(1200);
    const afterBurst = dummy.health;
    await sleep(1200);
    check(dummy.health === afterBurst, 'the bleed is over after the burst (no more ticks)', `${afterBurst} -> ${dummy.health}`);
  }

  // ---- Aegis Hammer Stagger: the third quick hit stuns for a second.
  console.log('   -- Stagger');
  clearSlimes();
  cmd('clear Smith');
  cmd('customweapon give Smith aegis_hammer');
  cmd('effect clear Dummy');
  cmd('tp Smith 0 -59 0');
  cmd('tp Dummy 2 -59 0');
  await healDummy();
  await sleep(800);
  await equipByName(smith, 'netherite_axe');
  const staggerTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  if (!staggerTarget) {
    fail('Smith can see Dummy for the Stagger');
  } else {
    await smith.lookAt(staggerTarget.position.offset(0, 1.2, 0), true);
    smith.actionBars.length = 0;
    dummy.actionBars.length = 0;
    for (let i = 0; i < 3; i++) {
      const h = dummy.health;
      smith.attack(staggerTarget);
      await waitUntil(() => dummy.health < h, 800);
      if (i < 2) {
        await sleep(200);
        check(!barHas(dummy, /^Staggered$/), `hit ${i + 1} of 3 does not stagger`, JSON.stringify(dummy.actionBars));
        await sleep(500);
      }
    }
    const staggered = await waitUntil(() => barHas(dummy, /^Staggered$/), 800);
    console.log(`   Stagger: Smith bar ${JSON.stringify(smith.actionBars)}; Dummy bar ${JSON.stringify(dummy.actionBars)}`);
    check(staggered, 'the third hit staggers: Dummy is told "Staggered"', JSON.stringify(dummy.actionBars.slice(-2)));
    check(barHas(smith, /^Stagger$/), 'Smith sees "Stagger"', JSON.stringify(smith.actionBars.slice(-2)));
    cmd('effect clear Dummy');
  }
  cmd('clear Smith');
  await sleep(600);

  // ------------------------------------------------------------- 9. SMP rules
  console.log('\n== 9. one of each weapon on the world ==');
  const setConfig = async (from, to) => {
    fs.writeFileSync(RUN + '/config/customweapons.json',
        fs.readFileSync(RUN + '/config/customweapons.json', 'utf8').replace(from, to));
    cmd('customweapon reload');
    await sleep(2000);
  };
  // Empty both inventories BEFORE switching the rule on: the sweep claims whatever is being
  // carried the instant uniqueness starts applying, which would claim the weapon left over
  // from the previous section.
  cmd('clear Smith');
  cmd('clear Dummy');
  await sleep(1500);
  await setConfig('"unique_weapons": false', '"unique_weapons": true');
  smith.chats.length = 0;
  dummy.chats.length = 0;
  cmd('customweapon give Smith bloodletter');
  await sleep(2500);
  check(smith.inventory.items().some((i) => i.name === 'netherite_sword'),
      'the first Bloodletter is handed out');
  check(dummy.chats.some((m) => /has forged/.test(m)),
      'the whole server is told a legendary was forged',
      dummy.chats.filter((m) => /forged/.test(m)).slice(-1)[0] || '');

  // A second one, by command: refused outright.
  cmd('customweapon give Dummy bloodletter');
  await sleep(2000);
  check(!dummy.inventory.items().some((i) => i.name === 'netherite_sword'),
      'a second Bloodletter cannot be given out');

  // A second one, by crafting: it turns back into a plain sword and the price comes back.
  console.log('   crafting a duplicate...');
  const dupe = await craftBloodletter(dummy);
  await sleep(2500);
  const dupeSword = dummy.inventory.items().find((i) => i.name === 'netherite_sword');
  console.log('   ' + describe(dupeSword));
  check(dupeSword && !/Bloodletter/.test(JSON.stringify(dupeSword)),
      'a crafted duplicate reverts to a plain netherite sword');
  check(dummy.inventory.items().some((i) => i.name === 'wither_skeleton_skull'),
      'the duplicate crafter gets their materials back',
      JSON.stringify(dummy.inventory.items().map((i) => i.name)));
  check(dummy.chats.some((m) => /already been forged/.test(m)),
      'and is told why', dummy.chats.filter((m) => /forged/.test(m)).slice(-1)[0] || '');

  // Releasing it lets the world have one again. Both inventories have to be empty first:
  // an unclaimed weapon that still exists in somebody's inventory re-claims itself on the
  // next sweep, which is what should happen when an admin unclaims one by mistake.
  cmd('clear Smith');
  cmd('clear Dummy');
  await sleep(1500);
  cmd('customweapon unclaim weapon bloodletter');
  await sleep(1500);
  cmd('customweapon give Dummy bloodletter');
  await sleep(2500);
  check(dummy.inventory.items().some((i) => i.name === 'netherite_sword'),
      'after /customweapon unclaim it can be forged again');

  // Leave nothing claimed and nothing carried, so the altar section starts clean.
  cmd('clear Dummy');
  await sleep(1500);
  cmd('customweapon unclaim weapon bloodletter');
  await sleep(1500);

  // ------------------------------------------------- 9b. one legendary per player
  console.log('\n== 9b. one legendary per player ==');
  // The main suite runs with the rule off so the earlier sections can hand weapons around
  // freely; switch it on for this check the same way the uniqueness rule was.
  cmd('clear Smith');
  cmd('tp Smith 0 -59 0');
  await sleep(1500);
  await setConfig('"one_weapon_per_player": false', '"one_weapon_per_player": true');
  smith.chats.length = 0;
  cmd('customweapon give Smith frostbrand');
  await sleep(1000);
  cmd('customweapon give Smith gale_edge');
  await sleep(1000);
  const names = smith.inventory.items().map((i) => i.name);
  const legendaries = smith.inventory.items().filter((i) => /Frostbrand|Gale Edge/.test(JSON.stringify(i)));
  console.log('   Smith carries ' + JSON.stringify(names));
  check(legendaries.length === 1 && legendaries[0].name === 'iron_sword',
      'the legendary they already carried is the one they keep',
      `${legendaries.length} legendary item(s): ${legendaries.map((i) => i.name).join(', ') || 'none'}`);
  check(!names.includes('diamond_sword'), 'the second one left the inventory');
  const onFloor = Object.values(smith.entities).filter((e) =>
      e.name === 'item' && e.position.distanceTo(smith.entity.position) < 4);
  check(onFloor.length >= 1, 'and is lying on the ground at their feet',
      `${onFloor.length} item entity(ies) within 4 blocks`);
  check(smith.chats.some((m) => /only one legendary/.test(m)), 'and they are told why',
      smith.chats.filter((m) => /legendary/.test(m)).slice(-1)[0] || '');
  // Off again, and nothing left on the floor or claimed, before the altar section.
  cmd('kill @e[type=minecraft:item]');
  cmd('clear Smith');
  await sleep(600);
  await setConfig('"one_weapon_per_player": true', '"one_weapon_per_player": false');
  cmd('customweapon unclaim weapon frostbrand');
  cmd('customweapon unclaim weapon gale_edge');
  await sleep(1000);

  // ------------------------------------------------------------------ 9. altars
  console.log('\n== 9. weapon altars ==');
  fs.writeFileSync(RUN + '/config/customweapons.json',
      fs.readFileSync(RUN + '/config/customweapons.json', 'utf8')
          .replace('"altars_enabled": false', '"altars_enabled": true'));
  cmd('customweapon reload');
  await sleep(2000);
  cmd('clear Smith');
  cmd('tp Smith 40 -59 40');
  await sleep(2000);
  cmd('execute as Smith at Smith run customweapon altar place bloodletter');
  await sleep(2000);
  // Stand off the shrine: a bot on top of the pedestal has no line of sight to it.
  const stepBack = async () => { cmd('tp Smith 43 -58 40'); await sleep(1200); };
  await stepBack();

  const lodestone = smith.findBlock({ matching: (b) => b.name === 'lodestone', maxDistance: 12 });
  check(!!lodestone, 'the altar structure was built',
      lodestone ? lodestone.position.toString() : 'no lodestone nearby');

  if (lodestone) {
    // A temple, not a plinth: count what actually got placed around the lodestone.
    const around = (name, r) => smith.findBlocks({
      point: lodestone.position, maxDistance: r, count: 500,
      matching: (b) => b.name === name,
    }).length;
    const parts = {
      deepslate_bricks: around('deepslate_bricks', 14),
      deepslate_tiles: around('deepslate_tiles', 14),
      soul_lantern: around('soul_lantern', 14),
      iron_chain: around('iron_chain', 14),
      soul_campfire: around('soul_campfire', 14),
      pillar: around('red_nether_bricks', 14),
    };
    console.log('   ' + JSON.stringify(parts));
    check(parts.deepslate_bricks + parts.deepslate_tiles > 250,
        'the temple is a real build, not a plinth',
        `${parts.deepslate_bricks + parts.deepslate_tiles} structural blocks`);
    check(parts.soul_lantern >= 8 && parts.iron_chain >= 16,
        'the colonnade carries its hanging lanterns',
        `${parts.soul_lantern} lanterns, ${parts.iron_chain} chains`);
    check(parts.soul_campfire === 4, 'four braziers ring the sanctum',
        `${parts.soul_campfire}`);
    check(parts.pillar >= 50, "the pillars are in the weapon's colours",
        `${parts.pillar} red nether bricks`);
  }

  // Exactly one set of labels for this altar, not one per chunk load, and carrying the real
  // text - a count alone would not notice duplicates or empty name tags.
  const nameOf = (e) => {
    const meta = (e.metadata || []).find((m) => m && (m.value || m.text || m.extra));
    return JSON.stringify(e.metadata || []);
  };
  const labels = Object.values(smith.entities || {}).filter((e) =>
      e.name === 'armor_stand' && lodestone
      && e.position.distanceTo(lodestone.position) < 3.5);
  const labelText = labels.map(nameOf).join(' ');
  console.log(`   ${labels.length} label entities on this altar`);
  console.log('   ' + labelText.slice(0, 400));
  check(labels.length === 5, 'the altar carries exactly one label per line',
      `${labels.length} armour stands within 3.5 blocks`);
  check(/Altar of the/.test(labelText), 'the label names the altar');
  // The name of an item in a label is a translation key; the client resolves it, so the
  // packet carries "item.minecraft.ghast_tear", not "Ghast Tear".
  // A skull is a block item, so its translation key is block.minecraft.*, not item.minecraft.*
  check(/minecraft\.wither_skeleton_skull/.test(labelText) && /3x /.test(labelText),
      'the label lists the price, at the new counts');

  if (lodestone) {
    const clickAltar = async () => {
      await stepBack();
      smith.chats.length = 0;
      await smith.activateBlock(smith.blockAt(lodestone.position));
      await sleep(1800);
    };

    // Empty-handed: the altar must say what it wants and hand out nothing.
    cmd('clear Smith');
    await clickAltar();
    console.log('   ' + JSON.stringify(smith.chats.slice(-5)));
    check(smith.chats.filter((m) => /need \d+x/.test(m)).length >= 3,
        'an empty-handed click lists what is missing');
    check(!smith.inventory.items().some((i) => i.name === 'netherite_sword'),
        'nothing is forged without the materials');

    // Part of the price is not the price.
    cmd('give Smith minecraft:netherite_sword 1');
    cmd('give Smith minecraft:wither_skeleton_skull 2');
    cmd('give Smith minecraft:ghast_tear 4');
    cmd('give Smith minecraft:netherite_ingot 1');
    await sleep(1500);
    await clickAltar();
    check(smith.chats.some((m) => /need 1x/.test(m)),
        'two skulls out of three is refused, and asks for one more',
        smith.chats.filter((m) => /need/.test(m)).join(' | '));
    cmd('clear Smith');
    await sleep(1000);

    // With the whole price in hand.
    cmd('give Smith minecraft:netherite_sword 1');
    cmd('give Smith minecraft:wither_skeleton_skull 3');
    cmd('give Smith minecraft:ghast_tear 4');
    cmd('give Smith minecraft:netherite_ingot 1');
    await sleep(1500);
    await clickAltar();
    const forged = smith.inventory.items().find((i) => i.name === 'netherite_sword');
    check(forged && /Bloodletter/.test(JSON.stringify(forged)),
        'the altar forges the weapon', forged ? 'got one' : 'nothing');
    check(!smith.inventory.items().some((i) => i.name === 'wither_skeleton_skull'),
        'the altar takes the materials');

    // The forged Bloodletter is itself a netherite sword. It must not be spent as the
    // ingredient for the next one.
    cmd('give Smith minecraft:wither_skeleton_skull 3');
    cmd('give Smith minecraft:ghast_tear 4');
    cmd('give Smith minecraft:netherite_ingot 1');
    await sleep(1500);
    await clickAltar();
    console.log('   ' + JSON.stringify(smith.chats.slice(-3)));
    const swords = smith.inventory.items().filter((i) => i.name === 'netherite_sword');
    check(smith.inventory.items().some((i) => i.name === 'wither_skeleton_skull'),
        'the second forge is refused: the materials are untouched');
    check(swords.length === 1 && /Bloodletter/.test(JSON.stringify(swords[0])),
        'the player still has exactly their one Bloodletter',
        `${swords.length} netherite sword(s)`);

    // The altar is spent, not broken: it says who beat you to it.
    cmd('give Smith minecraft:wither_skeleton_skull 3');
    cmd('give Smith minecraft:ghast_tear 4');
    cmd('give Smith minecraft:netherite_ingot 1');
    cmd('give Smith minecraft:netherite_sword 1');
    await sleep(1800);
    await clickAltar();
    console.log('   ' + JSON.stringify(smith.chats.slice(-2)));
    check(smith.chats.some((m) => /altar is spent/.test(m)),
        'a spent altar names whoever forged the weapon',
        smith.chats.slice(-1)[0] || '');
    check(smith.inventory.items().some((i) => i.name === 'wither_skeleton_skull'),
        'a spent altar takes nothing');

    // Nobody mines the altar out from under the server.
    await stepBack();
    const before = smith.blockAt(lodestone.position).name;
    try {
      await smith.dig(smith.blockAt(lodestone.position));
    } catch (e) {
      console.log('   dig refused: ' + e.message);
    }
    await sleep(1500);
    check(smith.blockAt(lodestone.position).name === before,
        'the altar block cannot be mined', smith.blockAt(lodestone.position).name);
    // The rest of the temple is protected too, and not only from picks.
    const lp = lodestone.position;
    const pillar = smith.findBlock({ matching: (b) => b.name === 'chiseled_deepslate' || /_bricks$|_block$|pillar/.test(b.name),
      point: lp.offset(7, -2, 7), maxDistance: 3 });
    if (pillar) {
      const pillarName = pillar.name;
      const pp = pillar.position;
      console.log(`   temple block ${pillarName} at ${pp} (altar at ${lp})`);
      // Stand next to it: a dig from eight blocks away is refused by vanilla as "too far",
      // which the client mispredicts as a break and proves nothing about the mod.
      cmd(`tp Smith ${pp.x - 1.5} ${pp.y} ${pp.z + 0.5}`);
      await sleep(1200);
      try { await Promise.race([smith.dig(smith.blockAt(pp)), sleep(12000)]); } catch (e) { console.log('   pillar dig refused: ' + e.message); }
      await sleep(1200);
      // The server's word, not the client's prediction.
      const offset = fs.readFileSync(RUN + '/test.log', 'utf8').length;
      cmd(`execute if block ${pp.x} ${pp.y} ${pp.z} minecraft:${pillarName}`);
      await sleep(800);
      const tail = fs.readFileSync(RUN + '/test.log', 'utf8').slice(offset);
      check(/Test passed/.test(tail), `a temple block (${pillarName}) cannot be mined either (server-side)`,
          (tail.match(/Test (passed|failed)/) || ['no echo'])[0]);
      await stepBack();
    } else {
      console.log('   (skip) no temple block found by the colonnade corner');
    }
    cmd(`summon minecraft:tnt ${lp.x + 0.5} ${lp.y + 1} ${lp.z + 0.5} {fuse:10}`);
    cmd(`summon minecraft:tnt ${lp.x + 1.5} ${lp.y + 1} ${lp.z + 0.5} {fuse:10}`);
    await sleep(2500);
    check(smith.blockAt(lp).name === before, 'TNT on the pedestal does not remove the altar', smith.blockAt(lp).name);
    const tntFloor = smith.blockAt(lp.offset(0, -4, 0));
    console.log('   floor under the altar after TNT: ' + (tntFloor ? tntFloor.name : '?'));
  }

  // Natural generation: walk into land the world has never generated before.
  console.log('\n== 10. altars generate in new chunks ==');
  cmd('tp Smith 6000 -59 6000');
  await sleep(4000);
  cmd('tp Smith 6600 -59 6600');
  await sleep(4000);
  cmd('tp Smith 7200 -59 7200');
  await sleep(5000);

  // Nothing from any effect may outlive its effect: the whole world, tagged or not.
  console.log('\n== 11. world effects left nothing behind ==');
  const taggedLeft = await fxCount();
  const anyLeft = await fxCount('', 'type=minecraft:block_display');
  const sent = [smith, dummy, archer].map((b) => `${b.username} ${b.fxSpawns.length} spawned/${b.fxGone.length} removed`);
  console.log(`   block displays sent to each client over the run: ${sent.join(', ')}`);
  check(taggedLeft === 0, 'no tagged effect displays remain at the end', `${taggedLeft}`);
  check(anyLeft === 0, 'no block displays of any kind remain at the end', `${anyLeft}`);
  for (const b of [smith, dummy, archer]) {
    check(b.fxGone.length >= b.fxSpawns.length,
        `${b.username} was told to remove every effect display it was sent`,
        `${b.fxGone.length}/${b.fxSpawns.length}`);
  }

  console.log(`\n=========== ${failures === 0 ? 'ALL BOT CHECKS PASSED' : failures + ' BOT CHECK(S) FAILED'} ===========`);
  for (const b of [smith, dummy, archer]) b.quit();
  await sleep(800);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.log('!! harness error: ' + (e && e.stack || e));
  process.exit(2);
});
