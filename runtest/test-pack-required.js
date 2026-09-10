// Verifies the required-pack path (config pack_required = true, the default):
//   - on join the server pushes the pack with forced=true and sends no chat buttons
//   - a DECLINED response to that pack gets the player disconnected with pack_kick_message
//   - ACCEPTED then SUCCESSFULLY_LOADED keeps the player connected
//
// Standalone: run-pack-test.sh boots the server, then runs `node test-pack-required.js`.
const mineflayer = require('/home/tim/claude/anticheat/test/node_modules/mineflayer');

const PORT = parseInt(process.env.PORT || '25603', 10);
const PACK_ID = '6f1c2a8e-3d4b-4c5e-9a0f-1b2c3d4e5f60';
const WANT_URL_PART = 'CustomWeapons-Models.zip';
const WANT_SHA1 = '7f9a282fac0db5a08e6a4e0a7560ea50c1eb41e1';

// ServerboundResourcePackPacket.Action ordinals, 1.20.3+ (minecraft-data leaves the varint
// unmapped for 1.21.11; mineflayer's own plugin uses the same first four values).
const RESULT = {
  SUCCESSFULLY_LOADED: 0, DECLINED: 1, FAILED_DOWNLOAD: 2, ACCEPTED: 3,
  DOWNLOADED: 4, INVALID_URL: 5, FAILED_RELOAD: 6, DISCARDED: 7,
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failures = 0;

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
    bot.chats = [];
    bot.rawChats = [];
    bot.packPackets = [];
    bot.kickReason = null;
    bot.ended = false;
    bot.on('message', (msg) => {
      bot.chats.push(msg.toString());
      try { bot.rawChats.push(JSON.stringify(msg.json !== undefined ? msg.json : msg)); } catch (e) { /* ignore */ }
    });
    bot._client.on('add_resource_pack', (p) => bot.packPackets.push(p));
    bot._client.on('packet', (data, meta) => {
      if (meta && meta.name && meta.name.toLowerCase().includes('resource')) {
        console.log(`   [packet] ${meta.name} state=${meta.state} ${JSON.stringify(data).slice(0, 400)}`);
      }
    });
    bot.on('kicked', (reason) => {
      bot.kickReason = typeof reason === 'string' ? reason : JSON.stringify(reason);
      console.log(`   [kicked] ${bot.username}: ${bot.kickReason.slice(0, 300)}`);
    });
    bot.on('end', () => { bot.ended = true; });
    // The kick may arrive before or after spawn, so resolve on either.
    let done = false;
    bot.once('spawn', () => { if (!done) { done = true; resolve(bot); } });
    bot.once('kicked', () => { if (!done) { done = true; resolve(bot); } });
    bot.on('error', (e) => { if (!done) { done = true; reject(e); } });
  });
}

function respond(bot, uuid, result) {
  bot._client.write('resource_pack_receive', { uuid, result });
}

async function waitForPack(bot, ms = 3000) {
  for (let i = 0; i < ms / 100; i++) {
    if (bot.packPackets.length) return bot.packPackets[0];
    await sleep(100);
  }
  return null;
}

async function main() {
  console.log('== Refuser: forced pack on join, DECLINED -> kick ==');
  let bot = await connect('Refuser');
  const pack = await waitForPack(bot);
  if (check(!!pack, 'Refuser received add_resource_pack on join')) {
    console.log('   packet fields: ' + JSON.stringify(pack));
    check(pack.forced === true, 'pack is forced', 'forced=' + pack.forced);
    check(pack.uuid === PACK_ID, 'pack id is the mod\'s fixed id', 'uuid=' + pack.uuid);
    check(pack.url.includes(WANT_URL_PART), 'url is the models zip', 'url=' + pack.url);
    check(pack.hash === WANT_SHA1, 'hash matches', 'hash=' + pack.hash);
  }
  await sleep(1500);   // give any chat offer time to arrive before asserting it did not
  check(!bot.chats.some((t) => t.includes('3D models')), 'no "3D models" chat offer',
        'chats=' + JSON.stringify(bot.chats).slice(0, 300));
  check(!bot.rawChats.some((r) => r.includes('/weaponpack')), 'no /weaponpack buttons in chat');

  if (pack) {
    respond(bot, pack.uuid, RESULT.DECLINED);
    console.log('   sent resource_pack_receive uuid=' + pack.uuid + ' result=DECLINED(1)');
    for (let i = 0; i < 30 && !bot.kickReason; i++) await sleep(100);
    if (check(!!bot.kickReason, 'Refuser was kicked within 3s of declining')) {
      check(/resource pack/i.test(bot.kickReason), 'kick reason mentions the resource pack',
            'reason=' + bot.kickReason.slice(0, 200));
    }
    await sleep(500);
    check(bot.ended || !!bot.kickReason, 'Refuser connection ended');
  }
  try { bot.quit(); } catch (e) { /* already gone */ }
  await sleep(1000);

  console.log('== Accepter: ACCEPTED + SUCCESSFULLY_LOADED -> stays ==');
  bot = await connect('Accepter');
  const pack2 = await waitForPack(bot);
  if (check(!!pack2, 'Accepter received add_resource_pack on join')) {
    check(pack2.forced === true, 'Accepter\'s pack is forced too');
    respond(bot, pack2.uuid, RESULT.ACCEPTED);
    await sleep(200);
    respond(bot, pack2.uuid, RESULT.DOWNLOADED);
    await sleep(200);
    respond(bot, pack2.uuid, RESULT.SUCCESSFULLY_LOADED);
    console.log('   sent ACCEPTED(3), DOWNLOADED(4), SUCCESSFULLY_LOADED(0)');
  }
  await sleep(3000);
  check(!bot.kickReason && !bot.ended, 'Accepter still connected after 3s',
        bot.kickReason ? 'kicked: ' + bot.kickReason.slice(0, 200) : '');
  // Prove the connection is live, not merely not-yet-closed.
  const before = bot.chats.length;
  bot.chat('still here');
  await sleep(700);
  check(bot.chats.length > before && bot.chats.some((t) => t.includes('still here')),
        'Accepter can still chat (own message echoed back)');
  bot.quit();
  await sleep(500);
}

main().then(() => {
  console.log(failures ? `\n${failures} FAILURE(S)` : '\nALL PASS');
  process.exit(failures ? 1 : 0);
}).catch((e) => {
  console.log('!! FAIL  test crashed: ' + (e.stack || e));
  process.exit(2);
});
