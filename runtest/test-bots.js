// Drives three vanilla clients against the test server.
//
// mineflayer speaks the plain vanilla protocol and knows nothing about this mod, which is
// exactly the point: if a bot can craft the weapon, see its name and stats, and feel its
// abilities, then so can a real player on an unmodified client.
const mineflayer = require('/home/tim/claude/anticheat/test/node_modules/mineflayer');
const fs = require('fs');

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

  // Back into melee range while the Momentum window is still open.
  cmd('tp Smith 1 -59 0');
  await sleep(500);
  smith.actionBars.length = 0;
  const momentumTarget = smith.players['Dummy'] && smith.players['Dummy'].entity;
  const dummyBeforeMomentum = dummy.health;
  if (momentumTarget) smith.attack(momentumTarget);
  await sleep(1200);
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
  smith.activateItem();                // on the ground: must slam
  await sleep(1500);
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
    const fullDrawAtDummy = async (label, arms) => {
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
        const before = dummy.health;
        archer.activateItem();
        await sleep(1400);
        archer.deactivateItem();
        if (arms) fullDraws++;
        await sleep(2500);
        const drop = before - dummy.health;
        console.log(`   Dummy ${before} -> ${dummy.health} (-${drop.toFixed(1)}); Archer bar `
            + JSON.stringify(archer.actionBars));
        if (drop > 0) return { drop, bars: archer.actionBars.slice() };
      }
      return null;
    };

    // Dummy wears no armour and has no effects, so the arrow is worth exactly 10 and the
    // shock exactly 6 on top. The arrow loses a whisker of speed over four blocks, hence
    // the tolerance.
    const shocked = await fullDrawAtDummy('full draw, shock ready', true);
    check(!!shocked, 'a fully drawn arrow lands and shocks');
    if (shocked) {
      shockHitAt = Date.now();
      check(Math.abs(shocked.drop - 16) <= 1.5,
          'with the shock ready a full draw deals 10 (arrow) + 6 (shock)',
          `${shocked.drop.toFixed(1)} hp`);
      check(shocked.bars.some((m) => /^Shock/.test(m.trim())),
          'the shooter sees the shock land', shocked.bars.slice(-1)[0] || '');

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
    for (let i = 0; i < 5; i++) {
      smith.attack(frostTarget);
      await sleep(600);
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
  }

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
    archer.deactivateItem();
    await sleep(2000);
    const yank = dummy.velocityPackets[dummy.velocityPackets.length - 1];
    const bar = archer.actionBars.filter((m) => /Harpoon/.test(m)).slice(-1)[0];
    console.log(`   Dummy velocity packets: ${dummy.velocityPackets.length}`
        + (yank ? ' ' + JSON.stringify(yank.velocity) : '') + (bar ? `; action bar "${bar}"` : ''));
    harpooned = !!yank || (!!bar && bar.trim() === 'Harpoon');
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
    archer.activateItem();
    await sleep(2500);
    console.log(`   Dummy ${dummyBeforeBolt} -> ${dummy.health}; velocity packets ${dummy.velocityPackets.length}; `
        + JSON.stringify(archer.actionBars));
    const armed = archer.actionBars.some((m) => m.trim() === 'Hellfire');
    const felt = dummy.health < dummyBeforeBolt || dummy.velocityPackets.length > 0;
    blasted = armed && felt;
    if (!blasted && attempt < 2) {
      await healDummy();
      await sleep(8500 - 1200);        // the next bolt is only armed once the cooldown is over
    }
  }
  check(archer.actionBars.some((m) => m.trim() === 'Hellfire'), 'the armed bolt reports on the action bar');
  check(blasted, 'the Hellfire bolt hurts the target it was fired at');

  // Leave the arena clean for the SMP rules: no stray weapons, no lingering effects.
  cmd('kill @e[type=minecraft:arrow]');
  cmd('kill @e[type=minecraft:item]');
  cmd('clear Archer');
  cmd('effect clear Dummy');
  cmd('effect clear Archer');
  await healDummy();
  console.log(`   section 8 took ${((Date.now() - section8Start) / 1000).toFixed(1)}s`);
  console.log(`   full draws fired with the shock ready: ${fullDraws}`);

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
  }

  // Natural generation: walk into land the world has never generated before.
  console.log('\n== 10. altars generate in new chunks ==');
  cmd('tp Smith 6000 -59 6000');
  await sleep(4000);
  cmd('tp Smith 6600 -59 6600');
  await sleep(4000);
  cmd('tp Smith 7200 -59 7200');
  await sleep(5000);

  console.log(`\n=========== ${failures === 0 ? 'ALL BOT CHECKS PASSED' : failures + ' BOT CHECK(S) FAILED'} ===========`);
  for (const b of [smith, dummy, archer]) b.quit();
  await sleep(800);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.log('!! harness error: ' + (e && e.stack || e));
  process.exit(2);
});
