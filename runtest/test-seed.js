// Verifies `/customweapon altar seed [radius]`: the command that gives a pre-generated world
// the altars it never grew. Standalone: run-seed-test.sh boots the server with
// altars_enabled, altars_per_weapon 3, altar_near_spawn_radius 250, spacing 6, then runs
// `node test-seed.js`. With ALTARS_OFF_AT_BOOT=1 the server boots with altars off and this
// script switches them on via `customweapon reload` before seeding, so every altar comes from
// the command ("Built 30"); by default the spawn area grows a few organically first.
//
// 1. bot "Seeder" joins, gets op over the console FIFO
// 2. /customweapon altar seed 600 -> reply lists 30 altars (3 per weapon, one of each within
//    250 blocks of the spawn), log has 30 "Weapon altar placed"
// 3. the same command again -> "Built 0 altar(s)" (idempotent)
// 4. /customweapon altar find names one of them
// 5. teleport onto every altar: a lodestone sits at the recorded position, exactly one
//    label entity per line floats above it; then revisit the first one after its chunk
//    was unloaded, to catch labels that multiply on every chunk load
const mineflayer = require('/home/tim/claude/anticheat/test/node_modules/mineflayer');
const fs = require('fs');

const RUN = __dirname;
const PORT = parseInt(process.env.PORT || '25603', 10);
const console_fifo = fs.createWriteStream(RUN + '/console.fifo', { flags: 'a' });

const WEAPONS = ['bloodletter', 'gale_edge', 'stormpiercer', 'aegis_hammer', 'frostbrand', 'tidecaller',
  'hellfire', 'dawnbreaker', 'voidreaper', 'starfall'];
const PER_WEAPON = 3;            // altars_per_weapon in the harness config
const NEAR_RADIUS = 250;         // altar_near_spawn_radius in the harness config
const TOTAL = WEAPONS.length * PER_WEAPON;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;

function cmd(c) { console_fifo.write(c + '\n'); }
function ok(name, extra = '') { console.log(`   PASS  ${name}${extra ? '  ' + extra : ''}`); }
function fail(name, extra = '') { failures++; console.log(`!! FAIL  ${name}${extra ? '  ' + extra : ''}`); }
function check(cond, name, extra = '') { cond ? ok(name, extra) : fail(name, extra); return cond; }

// mineflayer's toString() drops the innermost text of deeply nested components, so flatten
// the raw JSON ourselves: every `text` field in document order.
function flatten(node, out = []) {
  if (node == null) return out;
  if (typeof node === 'string') { out.push(node); return out; }
  if (Array.isArray(node)) { node.forEach((n) => flatten(n, out)); return out; }
  if (typeof node.text === 'string') out.push(node.text);
  if (node.translate && !node.text) out.push(node.translate);
  if (node.with) flatten(node.with, out);
  if (node.extra) flatten(node.extra, out);
  return out;
}

function connect(username) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      host: '127.0.0.1', port: PORT, username, version: '1.21.11', auth: 'offline',
    });
    bot.chats = [];
    bot.on('message', (msg) => {
      let text;
      try { text = flatten(msg.json !== undefined ? msg.json : msg).join(''); } catch (e) { text = msg.toString(); }
      if (!text) text = msg.toString();
      bot.chats.push(text);
    });
    bot.once('spawn', () => resolve(bot));
    bot.on('error', reject);
    bot.on('kicked', (r) => reject(new Error('kicked: ' + JSON.stringify(r))));
  });
}

async function waitChat(bot, re, timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const hit = bot.chats.find((t) => re.test(t));
    if (hit) return hit;
    await sleep(200);
  }
  return null;
}

// " - <Weapon Name>  x y z" lines that follow the "Built N altar(s)" summary
function altarLines(chats) {
  const out = [];
  for (const t of chats) {
    const m = t.match(/^\s*-\s+(.+?)\s{2,}(-?\d+) (-?\d+) (-?\d+)\s*$/);
    if (m) out.push({ name: m[1].trim(), x: +m[2], y: +m[3], z: +m[4] });
  }
  return out;
}

function placedLines() {
  const log = fs.readFileSync(RUN + '/test.log', 'utf8');
  const lines = log.split('\n').filter((l) => /Weapon altar placed:/.test(l));
  return lines.map((l) => {
    const m = l.match(/Weapon altar placed: (\S+) at (-?\d+) (-?\d+) (-?\d+)/);
    return m ? { weapon: m[1], x: +m[2], y: +m[3], z: +m[4], line: l } : { weapon: '?', line: l };
  });
}

async function main() {
  console.log('== 1. Seeder joins and is opped ==');
  const bot = await connect('Seeder');
  await sleep(1500);
  // The bot arrives at the world spawn; the near-spawn rule is measured from there.
  const spawn = { x: bot.entity.position.x, z: bot.entity.position.z };
  console.log(`   spawn is about ${spawn.x.toFixed(0)} ${spawn.z.toFixed(0)}`);
  cmd('op Seeder');
  await sleep(2000);
  if (process.env.ALTARS_OFF_AT_BOOT) {
    // The world so far was loaded with altars off, like a world pre-generated before the
    // mod. Switch them on now: from here on nothing is loaded organically, so every altar
    // has to come from the seed command.
    fs.writeFileSync(RUN + '/config/customweapons.json',
        fs.readFileSync(RUN + '/config/customweapons.json', 'utf8')
            .replace('"altars_enabled": false', '"altars_enabled": true'));
    cmd('customweapon reload');
    await sleep(2000);
    console.log('   altars were off at boot; switched on via reload');
  }
  const beforeSeed = placedLines();
  console.log(`   altars already placed organically before seeding: ${beforeSeed.length}`
      + (beforeSeed.length ? '  ' + beforeSeed.map((p) => `${p.weapon}@${p.x},${p.y},${p.z}`).join(' ') : ''));

  console.log('\n== 2. /customweapon altar seed 600 ==');
  bot.chats.length = 0;
  const t0 = Date.now();
  bot.chat('/customweapon altar seed 600');
  const built = await waitChat(bot, /Built \d+ altar\(s\)\. \d+ known in total/, 180000);
  const seedMs = Date.now() - t0;
  console.log(`   seed run took ${(seedMs / 1000).toFixed(1)}s`);
  await sleep(2500);   // the per-altar lines follow the summary
  check(!!built, 'the seed command replied within 180 s', built || JSON.stringify(bot.chats.slice(-5)));
  const builtN = built ? +built.match(/Built (\d+)/)[1] : -1;
  const knownN = built ? +built.match(/(\d+) known in total/)[1] : -1;
  console.log(`   reply: ${built}`);
  const listed = altarLines(bot.chats);
  for (const a of listed) console.log(`   - ${a.name}  ${a.x} ${a.y} ${a.z}`);
  check(knownN === TOTAL, `the reply says ${TOTAL} altars are known in total`, `${knownN}`);
  check(builtN + beforeSeed.length === TOTAL, 'the seed built every altar the spawn area had not already grown',
      `built ${builtN} + ${beforeSeed.length} organic`);
  check(listed.length === TOTAL, `the reply lists ${TOTAL} altars with coordinates`, `${listed.length} lines`);
  const perName = {};
  for (const a of listed) perName[a.name] = (perName[a.name] || 0) + 1;
  check(Object.keys(perName).length === WEAPONS.length
      && Object.values(perName).every((n) => n === PER_WEAPON),
      `the listed altars are ${PER_WEAPON} for each of the ${WEAPONS.length} weapons`, JSON.stringify(perName));
  check(listed.every((a) => Math.hypot(a.x, a.z) <= 600 + 64),
      'every altar lies within the requested radius (plus one chunk of slack)');

  const placed = placedLines();
  console.log(`   ${placed.length} "Weapon altar placed" lines in test.log`);
  check(placed.length === TOTAL, `test.log has exactly ${TOTAL} "Weapon altar placed" lines`, `${placed.length}`);
  const placedIds = placed.map((p) => p.weapon).sort();
  const expectedIds = WEAPONS.flatMap((w) => Array(PER_WEAPON).fill(w)).sort();
  check(JSON.stringify(placedIds) === JSON.stringify(expectedIds),
      `${PER_WEAPON} placed lines per weapon id`, placedIds.join(', '));
  // One of each weapon's temples lies near the spawn; the others may be anywhere.
  const nearBy = {};
  for (const p of placed) {
    const d = Math.hypot(p.x - spawn.x, p.z - spawn.z);
    if (d <= NEAR_RADIUS + 16) nearBy[p.weapon] = (nearBy[p.weapon] || 0) + 1;
  }
  console.log(`   temples within ${NEAR_RADIUS} of spawn: ${JSON.stringify(nearBy)}`);
  check(WEAPONS.every((w) => (nearBy[w] || 0) >= 1),
      `every weapon has a temple within ${NEAR_RADIUS} blocks of the spawn`,
      WEAPONS.filter((w) => !nearBy[w]).join(', ') || 'all');
  const farBy = {};
  for (const p of placed) {
    if (Math.hypot(p.x - spawn.x, p.z - spawn.z) > NEAR_RADIUS + 16) farBy[p.weapon] = (farBy[p.weapon] || 0) + 1;
  }
  check(Object.values(farBy).some((n) => n >= 1), 'and the rest are spread beyond it', JSON.stringify(farBy));
  // the reply and the log agree on where the altars are
  const listedKeys = new Set(listed.map((a) => `${a.x},${a.y},${a.z}`));
  check(placed.every((p) => listedKeys.has(`${p.x},${p.y},${p.z}`)),
      'the coordinates in the reply match the coordinates in the log');

  console.log('\n== 3. seed again: idempotent ==');
  bot.chats.length = 0;
  const t1 = Date.now();
  bot.chat('/customweapon altar seed 600');
  const again = await waitChat(bot, /Built \d+ altar\(s\)\. \d+ known in total/, 180000);
  console.log(`   second run took ${((Date.now() - t1) / 1000).toFixed(1)}s  reply: ${again}`);
  check(!!again && new RegExp(`Built 0 altar\\(s\\)\\. ${TOTAL} known in total`).test(again),
      `the second seed builds nothing and still knows ${TOTAL}`, again || '(no reply)');
  await sleep(1500);
  check(placedLines().length === TOTAL, 'no new "Weapon altar placed" lines after the second run',
      `${placedLines().length}`);

  console.log('\n== 4. /customweapon altar find ==');
  bot.chats.length = 0;
  bot.chat('/customweapon altar find');
  const found = await waitChat(bot, /Nearest altar: /, 10000);
  console.log(`   ${found}`);
  const fm = found && found.match(/Nearest altar: (\S+) at (-?\d+) (-?\d+) (-?\d+)/);
  check(!!fm && placed.some((p) => p.weapon === fm[1] && p.x === +fm[2] && p.y === +fm[3] && p.z === +fm[4]),
      'find names one of the seeded altars');

  console.log('\n== 5. the temples are really there ==');
  const { Vec3 } = require('/home/tim/claude/anticheat/test/node_modules/vec3');
  // Visit every altar: the lodestone at the recorded position, and exactly one label per
  // line (the main suite's standard: 5 stands, name + 4 ingredients), not a duplicated set.
  const labelKinds = {};
  for (const target of placed) {
    cmd(`tp Seeder ${target.x + 0.5} ${target.y + 1} ${target.z + 0.5}`);
    await sleep(3500);   // chunks around the altar stream in
    const altarPos = new Vec3(target.x, target.y, target.z);
    const block = bot.blockAt(altarPos);
    check(!!block && block.name === 'lodestone', `${target.weapon}: a lodestone sits at ${target.x} ${target.y} ${target.z}`,
        block ? block.name : 'block not loaded');
    const near = Object.values(bot.entities || {}).filter((e) =>
        e !== bot.entity && e.position && e.position.distanceTo(altarPos) < 3.5);
    const byType = {};
    for (const e of near) byType[e.name] = (byType[e.name] || 0) + 1;
    const textDisplays = near.filter((e) => e.name === 'text_display');
    const stands = near.filter((e) => e.name === 'armor_stand');
    const labels = textDisplays.length ? textDisplays : stands;
    const kind = textDisplays.length ? 'text_display' : 'armor_stand';
    labelKinds[kind] = (labelKinds[kind] || 0) + labels.length;
    const labelText = labels.map((e) => JSON.stringify(e.metadata || [])).join(' ');
    const names = labels.map((e) => {
      const m = JSON.stringify(e.metadata || []).match(/"(?:text|value)":"([^"]{2,80})"/);
      return m ? m[1] : '?';
    });
    console.log(`   ${target.weapon}: entities within 3.5 of the altar ${JSON.stringify(byType)}`);
    check(labels.length === 5, `${target.weapon}: exactly one label per line (${kind})`,
        `${labels.length} label entities: ${names.join(' | ').slice(0, 300)}`);
    check(/Altar of the/.test(labelText), `${target.weapon}: the labels carry the altar name`);
  }

  // Come back to the first altar now that the others have pushed its chunk out of view:
  // a count that grows on every reload is a label leak in the chunk-load path, a count
  // that stays doubled is a duplicate made at seed time.
  {
    const target = placed[0];
    cmd(`tp Seeder ${target.x + 0.5} ${target.y + 1} ${target.z + 0.5}`);
    await sleep(3500);
    const altarPos = new Vec3(target.x, target.y, target.z);
    const stands = Object.values(bot.entities || {}).filter((e) =>
        e !== bot.entity && e.position && e.name === 'armor_stand' && e.position.distanceTo(altarPos) < 3.5);
    console.log(`   revisit ${target.weapon}: ${stands.length} label stands after its chunk was unloaded and reloaded`);
    check(stands.length === 5, `${target.weapon} revisited: still exactly one label per line`, `${stands.length}`);
  }

  // enough of a temple to be more than a lodestone (checked at the last altar visited)
  const structural = bot.findBlocks({
    point: bot.entity.position, maxDistance: 14, count: 600,
    matching: (b) => b.name === 'deepslate_bricks' || b.name === 'deepslate_tiles',
  }).length;
  check(structural > 250, 'the seeded altar is a full temple', `${structural} structural blocks`);
  console.log('   label entity kinds seen: ' + JSON.stringify(labelKinds));

  console.log('\n== summary ==');
  console.log('   altars: ' + placed.map((p) => `${p.weapon} ${p.x} ${p.y} ${p.z}`).join(' | '));
  console.log(`   seed run: ${(seedMs / 1000).toFixed(1)}s, built ${builtN}, ${knownN} known`);
  bot.quit();
  await sleep(500);
  console.log(failures === 0 ? '\nRESULT: PASS' : `\nRESULT: FAIL (${failures} failure(s))`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('!! test crashed:', e);
  process.exit(2);
});
