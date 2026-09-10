#!/usr/bin/env bash
# Boots the 1.21.11 Fabric test server exactly like test.sh (same config, FIFO console,
# test.log), runs test-pack.js against it, then stops the server via the console FIFO.
set -u

RUN="$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-25603}"
cd "$RUN" || exit 1

rm -f console.fifo test.log pack.log
rm -rf world usercache.json
mkdir -p config
# Same config test.sh uses (log_abilities on, so "PACK sent to" appears). Extracted from
# test.sh so the two never drift apart.
sed -n "/<<'JSON'/,/^JSON$/p" test.sh | sed '1d;$d' > config/customweapons.json
grep -q '"log_abilities": true' config/customweapons.json || { echo "!! config extraction failed"; exit 1; }

mkfifo console.fifo
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
RESULT=0

echo
echo "=========== MIXIN / BOOT ==========="
if grep -iE "mixin" test.log | grep -iE "error|exception|failed|could not|unable" ; then
    echo "!! FAIL  mixin errors in the server log"; RESULT=1
else
    echo "   PASS  no mixin errors during boot"
fi

echo
echo "=========== PHASE 1: pack_required = true (default) ==========="
PORT="$PORT" node test-pack-required.js 2>&1 | grep -vE "$NOISE" | tee pack.log
[ "${PIPESTATUS[0]}" = 0 ] || RESULT=1
echo "--- server log ---"
grep -E "PACK |Refuser|Accepter" test.log | grep -vE "logged in|joined the game|left the game"

echo
echo "=========== PHASE 2: pack_required = false, live reload, chat offer ==========="
sed -i 's/^  "log_abilities": true$/  "pack_required": false,\n  "log_abilities": true/' config/customweapons.json
grep -q '"pack_required": false' config/customweapons.json || { echo "!! config edit failed"; exit 1; }
echo "customweapon reload" >&3
waitfor 'config reloaded' 10 || RESULT=1
PORT="$PORT" node test-pack.js 2>&1 | grep -vE "$NOISE" | tee -a pack.log
[ "${PIPESTATUS[0]}" = 0 ] || RESULT=1

echo
echo "=========== SERVER LOG ==========="
grep -E "PACK sent|weaponpack|customweapons-pack-declined" test.log || echo "(no PACK lines)"
echo "--- declined file ---"
cat world/customweapons-pack-declined.json 2>/dev/null || echo "(missing)"
echo
echo "=========== EXCEPTIONS ==========="
grep -iE "exception|ERROR\]" test.log | grep -v "No key layers" | head -10 || true
echo "(end)"
exit $RESULT
