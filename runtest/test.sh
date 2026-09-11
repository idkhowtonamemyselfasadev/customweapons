#!/usr/bin/env bash
# Boots a real 1.21.11 Fabric dedicated server with the mod in mods/, then drives three
# vanilla mineflayer clients against it. Nothing here is stubbed: real crafting, real
# damage, real packets.
set -u

RUN="$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-25603}"
cd "$RUN" || exit 1

rm -f console.fifo test.log bots.log
rm -rf world usercache.json
mkdir -p config
# Ability logging on, so the assertions can be about what fired, not just about health bars.
cat > config/customweapons.json <<'JSON'
{
  "bloodletter_enabled": true,
  "bloodletter_attack_damage": 4.0,
  "bloodletter_attack_speed": 2.0,
  "bleed_max_stacks": 3,
  "bleed_duration_ticks": 60,
  "bleed_tick_interval_ticks": 10,
  "bleed_damage_per_stack": 1.5,
  "bleed_damage_budget": 12.0,
  "gale_edge_enabled": true,
  "gale_edge_attack_damage": 7.0,
  "gale_edge_attack_speed": 1.8,
  "dash_cooldown_ticks": 160,
  "dash_power": 1.5,
  "dash_min_y": 0.3,
  "dash_max_y": 0.8,
  "dash_fall_immunity_ticks": 120,
  "momentum_window_ticks": 40,
  "momentum_bonus_damage": 4.0,
  "stormpiercer_enabled": true,
  "stormpiercer_full_damage": 10.0,
  "shock_cooldown_ticks": 600,
  "shock_min_arrow_speed": 2.7,
  "shock_bonus_damage": 6.0,
  "shock_glowing_ticks": 120,
  "shock_chain_range": 5.0,
  "shock_chain_damage": 3.0,
  "shock_lightning": true,
  "shock_lightning_fire": false,
  "shock_stun_ticks": 40,
  "shock_instakill": ["minecraft:creeper", "minecraft:skeleton"],
  "frostbrand_enabled": true,
  "frostbrand_attack_damage": 8.0,
  "frostbrand_attack_speed": 1.6,
  "frost_beam_cooldown_ticks": 160,
  "frost_ticks_per_hit": 70,
  "frost_slowness_ticks": 40,
  "frost_slowness_amplifier": 1,
  "shatter_damage": 8.0,
  "shatter_slowness_ticks": 40,
  "shatter_slowness_amplifier": 3,
  "tidecaller_enabled": true,
  "tidecaller_attack_damage": 10.0,
  "tidecaller_attack_speed": 1.1,
  "tide_wet_bonus_damage": 4.0,
  "harpoon_cooldown_ticks": 200,
  "harpoon_pull_power": 1.4,
  "harpoon_pull_lift": 0.35,
  "hellfire_enabled": true,
  "hellfire_cooldown_ticks": 160,
  "hellfire_explosion_power": 2.0,
  "hellfire_fire": false,
  "aegis_hammer_enabled": true,
  "aegis_hammer_attack_damage": 11.0,
  "aegis_hammer_attack_speed": 0.9,
  "slam_cooldown_ticks": 300,
  "slam_radius": 5.0,
  "slam_damage": 8.0,
  "slam_slowness_ticks": 80,
  "slam_slowness_amplifier": 1,
  "slam_resistance_ticks": 100,
  "slam_knock_up": 0.35,
  "slam_knock_out": 0.4,
  "unique_weapons": false,
  "one_weapon_per_player": false,
  "altars_enabled": false,
  "altar_spacing_chunks": 6,
  "stat_sweep_interval_ticks": 5,
  "world_effects": true,
  "log_abilities": true
}
JSON

mkfifo console.fifo
# Always test the jar that was just built: a stale copy in mods/ once ran a whole suite
# against last week's abilities and reported them missing.
if [ -f "$RUN/../build/libs/customweapons-1.0.0.jar" ]; then
    cp "$RUN/../build/libs/customweapons-1.0.0.jar" mods/customweapons-1.0.0.jar
fi
echo "== mod jar: $(ls -la mods/customweapons-1.0.0.jar | awk '{print $5, $6, $7, $8}') =="
java -Xmx1500M -jar fabric-server-launch.jar nogui < console.fifo > test.log 2>&1 &
SERVER_PID=$!
# Hold the FIFO open, or the server sees EOF on stdin and shuts itself down.
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

echo "== booting 1.21.11 Fabric server on port $PORT =="
waitfor 'Done \(' 180 || { tail -30 test.log; exit 1; }
waitfor 'CustomWeapons ready' 15 || { tail -30 test.log; exit 1; }
grep -E "CustomWeapons ready" test.log

echo
echo "== recipes loaded without error =="
if grep -iE "(Parsing error loading|Couldn't parse|Failed to (parse|load)).*(recipe|customweapons)" test.log; then
    echo "!! FAIL  recipe json rejected by the server"
else
    echo "   PASS  no recipe parse errors in the server log"
fi

# mineflayer's protocol tables for 1.21.11 are behind on particles and attribute ids, so it
# logs partial-read noise for packets a real client handles fine. Filter it out.
PORT="$PORT" node test-bots.js 2>&1 | grep -vE "^Chunk size is|^PartialReadError|^\s+at |DeprecationWarning|trace-deprecation" | tee bots.log
RESULT=${PIPESTATUS[0]}

echo
echo "=========== SERVER-SIDE ASSERTIONS ==========="
assert_log() {  # description, pattern, expected-count-comparison
    local desc="$1" pattern="$2" op="$3" want="$4"
    local n
    n=$(grep -cE "$pattern" test.log)
    if [ "$n" "$op" "$want" ]; then
        echo "   PASS  $desc  (${n} matching lines)"
    else
        echo "!! FAIL  $desc  (${n} lines, wanted $op $want)"
        RESULT=1
    fi
}
assert_log "bleed applied and reached three stacks" "ABILITY bleed apply .* stacks=3" -ge 1
assert_log "bleed ticked repeatedly"                "ABILITY bleed tick"              -ge 4
assert_log "the dash fired"                         "ABILITY dash"                    -ge 1
assert_log "the Momentum Strike fired"              "ABILITY momentum"                -ge 1
assert_log "the slam fired and found a target"      "ABILITY slam .* targets=[1-9]"   -ge 1
# One armed arrow per full draw the bots fired with the shock ready (section 7's hit on
# Dummy, section 8's shot at the creeper, and any retried misses); none for the partial
# draw and none for section 7's full draw inside the cooldown.
FULL_DRAWS=$(grep -oE "full draws fired with the shock ready: [0-9]+" bots.log | tail -1 | grep -oE "[0-9]+$")
assert_log "exactly one arrow armed per ready full draw (${FULL_DRAWS:-?} fired), none for the partial or cooldown ones" \
           "ABILITY shock arm"                                                -eq "${FULL_DRAWS:-1}"
assert_log "the shock landed"                       "ABILITY shock hit"               -ge 1
assert_log "the shock on Dummy was the +6.0 bonus alone, nothing chained" \
           "ABILITY shock hit victim=Dummy chained=none total=6.0"            -ge 1
assert_log "the shock stunned the target"          "ABILITY stun victim=Dummy ticks=40" -ge 1
assert_log "the shock executed a creeper"           "ABILITY shock hit victim=Creeper .* executed=true" -ge 1
assert_log "frost stacked on a target"              "ABILITY frost"                   -ge 2
assert_log "the shatter fired"                      "ABILITY shatter"                 -ge 1
assert_log "the ice beam froze the target (twice: the walk probe and the rigid-freeze probe)" \
           "ABILITY frostbeam player=Smith victim=Dummy" -ge 2
assert_log "the ice beam froze the husk"            "ABILITY frostbeam player=Smith victim=Husk"  -ge 1
assert_log "the ice beam can miss"                  "ABILITY frostbeam player=Smith victim=miss"  -ge 1
assert_log "the harpoon fired"                      "ABILITY harpoon"                 -ge 1
assert_log "a daytime Dawnbreaker hit added the Solar Brand" "ABILITY solar player=Smith victim=Dummy bonus=2.0" -ge 1
assert_log "the Sunstrike landed on Dummy"          "ABILITY sunstrike player=Smith .* hits=1" -ge 1
assert_log "the Rift fired on Dummy"                "ABILITY rift player=Smith victim=Dummy"  -ge 1
assert_log "the backstab landed"                    "ABILITY backstab player=Smith victim=Dummy bonus=6.0" -ge 1
assert_log "the Soul Harvest fed the wielder"       "ABILITY harvest player=Smith victim=Zombie heal=4.0" -ge 1
assert_log "the Comet launched"                     "ABILITY comet player=Smith"             -ge 1
assert_log "the landing was the impact and hit Dummy" "ABILITY impact player=Smith hits=1 .* struck=ground" -ge 1
assert_log "the Exsanguinate burst one bleed"       "ABILITY exsanguinate player=Smith victims=1" -ge 1
assert_log "the Stagger stunned Dummy"              "ABILITY stagger player=Smith victim=Dummy" -ge 1
assert_log "a Hellfire bolt was armed"              "ABILITY hellfire arm"            -ge 1
assert_log "the Hellfire bolt exploded"             "ABILITY hellfire explode"        -ge 1
assert_log "the one-legendary rule dropped the second weapon, keeping the first" \
           "LIMIT Smith dropped gale_edge \(carrying frostbrand\)"            -ge 1
assert_log "the altar forged a weapon"              "ALTAR forge .* weapon=bloodletter" -ge 1
assert_log "altars generate in newly generated chunks" "Weapon altar placed"           -ge 1
# World effects: the keyframes in effects.json parse into the records at boot, and the
# block displays they play never raise from the tick loop or the display mixins.
assert_log "all eighteen world effects loaded from effects.json" "Loaded 18 world effects"     -ge 1
assert_log "effects.json was readable"              "Could not read effects.json"     -eq 0
assert_log "no exception out of the effects player or its display mixins" \
           "dev\.customweapons\.(Effects|mixin\.(Block)?DisplayInvoker)"            -eq 0
assert_log "no leftover effect entities had to be swept on a fresh world" \
           "Removed [0-9]+ leftover effect entities"                          -eq 0
# Rigid freeze: the knockback mixin and the stun list never raise from a hit or a kill.
assert_log "no exception out of the knockback mixin or the stuns" \
           "dev\.customweapons\.(mixin\.KnockbackMixin|Stuns)"                        -eq 0

echo
echo "--- bleed budget: the sum of one bleed's ticks must not exceed 12.0 ---"
grep -oE "ABILITY bleed tick .*budget_left=[0-9.]+" test.log | tail -12
echo
echo "=========== ABILITY LOG (server side) ==========="
grep -oE "ABILITY .*" test.log | head -40
echo
echo "=========== EXCEPTIONS ==========="
grep -iE "exception|ERROR\]" test.log | grep -v "No key layers" | head -10 || true
echo "(end)"
exit $RESULT
