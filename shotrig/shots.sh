#!/usr/bin/env bash
# In-game screenshots of every ability: boots a private Fabric server on port 25604 with the
# mod, then drives a hidden dev client (weston headless) through script.txt with the pack
# loaded. Screenshots land in shots/.
#   ./shots.sh            full run
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
MOD="$(dirname "$DIR")"
SRV="$DIR/server"
GRADLE="$HOME/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle"
[ -x "$GRADLE" ] || GRADLE="$HOME/opt/gradle-9.6.1/bin/gradle"
export JAVA_HOME=/home/tim/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
[ -d "$JAVA_HOME" ] || export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
PORT=25604

# Tim's own game may be running: the hidden client only shares the GPU with it for a few
# minutes. Nothing here kills a java process; the client exits itself at the end of the script.

# --- server: a copy of the test server on its own port, pack push off (the client loads the pack locally)
mkdir -p "$SRV"
rsync -a --exclude world --exclude logs --exclude '*.log' --exclude console.fifo --exclude libraries --exclude versions "$MOD/runtest/" "$SRV/" 2>/dev/null
# the test server keeps its libraries as links into run/; from one folder deeper the links must be re-pointed
[ -e "$SRV/libraries" ] || ln -s ../../run/libraries "$SRV/libraries"
[ -e "$SRV/versions" ] || ln -s ../../run/versions "$SRV/versions"
rm -rf "$SRV/world"
cp "$MOD/build/libs/customweapons-1.6.0.jar" "$SRV/mods/"; rm -f "$SRV"/mods/customweapons-1.0.0.jar
sed -i "s/^server-port=.*/server-port=$PORT/" "$SRV/server.properties"
grep -q "^level-type=" "$SRV/server.properties" && sed -i 's/^level-type=.*/level-type=minecraft\\:flat/' "$SRV/server.properties"
mkdir -p "$SRV/config"
cat > "$SRV/config/customweapons.json" <<JSON
{ "unique_weapons": false, "one_weapon_per_player": false, "altars_enabled": false, "pack_required": false,
  "pack_offer_on_join": false, "world_effects": ${CW_FX:-true}, "animation_ticks": ${CW_ANIM:-10}, "log_abilities": true }
JSON
echo '[{"uuid":"2474819f-b90f-3892-b735-07407c3c3e92","name":"ShotRig","level":4,"bypassesPlayerLimit":false}]' > "$SRV/ops.json"
rm -f "$SRV/console.fifo"; mkfifo "$SRV/console.fifo"
( cd "$SRV" && java -Xmx1500M -jar fabric-server-launch.jar nogui < console.fifo > server.log 2>&1 ) &
SERVER_PID=$!
exec 3> "$SRV/console.fifo"
cleanup() { echo "stop" >&3 2>/dev/null; wait $SERVER_PID 2>/dev/null; exec 3>&- 2>/dev/null; rm -f "$SRV/console.fifo"; }
trap cleanup EXIT
for i in $(seq 1 180); do grep -q 'Done (' "$SRV/server.log" 2>/dev/null && break; sleep 1; done
grep -q 'Done (' "$SRV/server.log" || { echo "server did not boot"; tail -20 "$SRV/server.log"; exit 1; }
# the dev client's offline uuid is not known in advance: op by name once it is in
echo "op ShotRig" >&3
echo "gamerule spawn_mobs false" >&3
echo "kill @e[type=!player]" >&3

# --- client
ls "$XDG_RUNTIME_DIR/wl-mc" >/dev/null 2>&1 || { weston --backend=headless --xwayland --socket=wl-mc --width=1920 --height=1080 --idle-time=0 > "$DIR/weston.log" 2>&1 & sleep 4; }
cp "$MOD/release/CustomWeapons-Models.zip" "$DIR/run/resourcepacks/"
rm -rf "$DIR/run/screenshots"; mkdir -p "$DIR/run/screenshots"
# display and script go in as a project property: the daemon's own environment is not trusted
timeout 600 "$GRADLE" -p "$DIR" runClient -q "-Pcwrig=$DIR/${SCRIPT:-script.txt}|127.0.0.1:$PORT|${RIG_DISPLAY:-:1}|${RIG_WAYLAND:-wl-mc}" > "$DIR/runclient.log" 2>&1
echo "client exit $?"
# grant op again in case the client joined before the first op landed, and collect
mkdir -p "$DIR/shots"; rm -f "$DIR"/shots/*.png
cp "$DIR"/run/screenshots/*.png "$DIR/shots/" 2>/dev/null
echo "$(ls "$DIR"/shots/*.png 2>/dev/null | wc -l) screenshots in $DIR/shots"
grep -a -E "script:|screenshot|ShotRig ready|connecting|Exception|crashed" "$DIR/runclient.log" | grep -v "JAVA_TOOL\|Realms" | tail -15 | cut -c1-200
grep -E "ABILITY|LIMIT|Loaded .* world effects" "$SRV/server.log" | cut -c1-160 | tail -30
