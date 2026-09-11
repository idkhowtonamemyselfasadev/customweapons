#!/usr/bin/env bash
# Boots the 1.21.11 Fabric test server like test.sh (FIFO console, test.log, fresh flat
# world) but with altars on from the start, then runs test-seed.js against it and stops the
# server via the console FIFO. Verifies `/customweapon altar seed`.
set -u

RUN="$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-25603}"
cd "$RUN" || exit 1

rm -f console.fifo test.log seed.log
rm -rf world usercache.json
mkdir -p config
# test.sh's config, with the altar rules this test is about. Extracted rather than copied so
# the weapon numbers never drift from the main suite.
#
# ALTARS_OFF_AT_BOOT=1 boots with altars_enabled false and lets test-seed.js flip it on via
# `customweapon reload` just before seeding. That is the real "pre-generated world" case:
# the spawn chunks are loaded while the mod builds nothing, so the command has all 7 to
# build. The default boots with altars on, so the spawn area grows some organically first.
BOOT_ALTARS="${ALTARS_OFF_AT_BOOT:+false}"; BOOT_ALTARS="${BOOT_ALTARS:-true}"
sed -n "/<<'JSON'/,/^JSON$/p" test.sh | sed '1d;$d' \
    | sed -e "s/^  \"altars_enabled\": false,\$/  \"altars_enabled\": $BOOT_ALTARS,/" \
          -e 's/^  "altar_spacing_chunks": [0-9]*,$/  "altar_spacing_chunks": 6,/' \
          -e 's/^  "unique_weapons": true,$/  "unique_weapons": false,/' \
    > config/customweapons.json
for want in "\"altars_enabled\": $BOOT_ALTARS" '"altars_per_weapon": 3' '"altar_near_spawn_radius": 250' '"altar_spacing_chunks": 6' '"unique_weapons": false'; do
    grep -q "$want" config/customweapons.json || { echo "!! config extraction failed: missing $want"; exit 1; }
done

mkfifo console.fifo
# The jar that was just built, not whatever the sync left in mods/: this runner once tested
# the previous release and reported the new placement rules missing.
if [ -f "$RUN/../build/libs/customweapons-1.0.0.jar" ]; then
    cp "$RUN/../build/libs/customweapons-1.0.0.jar" mods/customweapons-1.0.0.jar
fi
echo "== mod jar: $(ls -la mods/customweapons-1.0.0.jar | awk '{print $5, $6, $7, $8}') =="
java -Xmx1500M -jar fabric-server-launch.jar nogui < console.fifo > test.log 2>&1 &
SERVER_PID=$!
exec 3> console.fifo

cleanup() {
    echo "stop" >&3 2>/dev/null
    wait $SERVER_PID 2>/dev/null
    exec 3>&- 2>/dev/null
    rm -f console.fifo
}
trap cleanup EXIT

waitfor() {
    local pattern="$1" limit="${2:-90}" i=0
    while [ $i -lt "$limit" ]; do
        grep -qE "$pattern" test.log && return 0
        command sleep 1
        i=$((i + 1))
    done
    echo "!! TIMED OUT waiting for: $pattern"
    return 1
}

echo "== booting 1.21.11 Fabric server on port $PORT (pid $SERVER_PID) =="
waitfor 'Done \(' 180 || { tail -30 test.log; exit 1; }
waitfor 'CustomWeapons ready' 15 || { tail -30 test.log; exit 1; }
grep -E "CustomWeapons ready" test.log

NOISE='^Chunk size is|^PartialReadError|^\s+at |DeprecationWarning|trace-deprecation'
ALTARS_OFF_AT_BOOT="${ALTARS_OFF_AT_BOOT:-}" PORT="$PORT" node test-seed.js 2>&1 | grep -vE "$NOISE" | tee seed.log
RESULT=${PIPESTATUS[0]}

echo
echo "=========== SERVER LOG: altars ==========="
grep -E "Weapon altar placed|Altar seeding failed|Altar placement failed" test.log || echo "(no altar lines)"
echo
echo "=========== EXCEPTIONS ==========="
grep -iE "exception|ERROR\]" test.log | grep -v "No key layers" | head -10 || true
echo "(end)"
exit $RESULT
