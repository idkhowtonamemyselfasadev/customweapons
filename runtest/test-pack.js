// Verifies the resource-pack offer: the chat question on join, the clickable answers, the
// actual ClientboundResourcePackPushPacket after /weaponpack install, and that /weaponpack
// never silences the question on the next join.
//
// Standalone: run-pack-test.sh boots the server, then runs `node test-pack.js`.
const mineflayer = require('/home/tim/claude/anticheat/test/node_modules/mineflayer');

const PORT = parseInt(process.env.PORT || '25603', 10);
const WANT_URL_PART = 'CustomWeapons-Models.zip';
const WANT_SHA1 = '7f9a282fac0db5a08e6a4e0a7560ea50c1eb41e1';

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
    bot.chats = [];        // plain text of every chat/system message
    bot.rawChats = [];     // JSON.stringify of the parsed ChatMessage (click events live here)
    bot.packPackets = [];  // add_resource_pack packets
    bot.resourcePacketNames = new Set();
    bot.on('message', (msg) => {
      bot.chats.push(msg.toString());
      let raw = '';
      try { raw = JSON.stringify(msg.json !== undefined ? msg.json : msg); } catch (e) { raw = String(msg); }
      bot.rawChats.push(raw);
    });
    bot._client.on('add_resource_pack', (p) => bot.packPackets.push(p));
    bot._client.on('packet', (data, meta) => {
      if (meta && meta.name && meta.name.toLowerCase().includes('resource')) {
        bot.resourcePacketNames.add(meta.name);
        console.log(`   [packet] ${meta.name} state=${meta.state} ${JSON.stringify(data).slice(0, 400)}`);
      }
    });
    bot.once('spawn', () => resolve(bot));
    bot.on('error', reject);
    bot.on('kicked', (r) => reject(new Error('kicked: ' + JSON.stringify(r))));
  });
}

function offered(bot) {
  return bot.chats.some((t) => t.includes('3D models'));
}

async function main() {
  console.log('== first join: expect the pack offer ==');
  let bot = await connect('Packer');
  await sleep(3000);

  check(offered(bot), 'chat message containing "3D models" arrived on join',
        offered(bot) ? '' : 'chats=' + JSON.stringify(bot.chats).slice(0, 400));
  const rawWithInstall = bot.rawChats.find((r) => r.includes('/weaponpack install'));
  check(!!rawWithInstall, 'a message carries a click event for /weaponpack install',
        rawWithInstall ? rawWithInstall.slice(0, 600) : 'raw=' + bot.rawChats.join(' | ').slice(0, 600));
  check(bot.rawChats.some((r) => r.includes('/weaponpack later')), 'a click event for /weaponpack later exists');
  check(bot.rawChats.some((r) => r.includes('/weaponpack never')), 'a click event for /weaponpack never exists');
  check(bot.rawChats.some((r) => r.includes('run_command')), 'the click action is run_command');

  console.log('== /weaponpack install: expect a resource pack push packet ==');
  const before = bot.packPackets.length;
  bot.chat('/weaponpack install');
  let got = null;
  for (let i = 0; i < 30 && !got; i++) {
    await sleep(100);
    if (bot.packPackets.length > before) got = bot.packPackets[bot.packPackets.length - 1];
  }
  if (check(!!got, 'client received an add_resource_pack packet within 3s',
            'resource-ish packet names seen: ' + JSON.stringify([...bot.resourcePacketNames]))) {
    console.log('   packet fields: ' + JSON.stringify(got));
    check(typeof got.url === 'string' && got.url.includes(WANT_URL_PART),
          `packet url contains ${WANT_URL_PART}`, 'url=' + got.url);
    check(got.hash === WANT_SHA1, 'packet hash matches the configured sha1', 'hash=' + got.hash);
    check(got.forced === false, 'pack is not forced', 'forced=' + got.forced);
    check(!!got.promptMessage, 'a prompt message is attached');
  }
  await sleep(500);
  check(bot.chats.some((t) => t.includes('Sending the 3D weapon models')),
        'server confirmed the send in chat');

  console.log('== /weaponpack never, then rejoin: expect silence ==');
  bot.chat('/weaponpack never');
  await sleep(1000);
  check(bot.chats.some((t) => t.includes('no more asking')), 'server acknowledged /weaponpack never');
  bot.quit();
  await sleep(1500);

  bot = await connect('Packer');
  await sleep(3000);
  check(!offered(bot), 'no "3D models" offer on the second join',
        offered(bot) ? 'chats=' + JSON.stringify(bot.chats).slice(0, 400) : `chats=${bot.chats.length}`);
  check(bot.packPackets.length === 0, 'no pack pushed unasked on the second join');
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
