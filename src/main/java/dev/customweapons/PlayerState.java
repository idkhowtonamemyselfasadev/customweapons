package dev.customweapons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived per-player flags: the Gale Edge's fall immunity, its Momentum Strike window,
 * and whether the player has spent their one air dash.
 *
 * <p>All of it is deliberately in memory only. None of it should survive a restart - a
 * player logging back in mid-flight with six seconds of banked fall immunity would be a bug,
 * not a feature.
 */
public final class PlayerState {

    private static final class State {
        long fallImmuneUntil;
        long momentumUntil;
        boolean airDashUsed;
        /** The legendary this player is carrying, so a second one is the one to drop. */
        String carrying;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Cooldowns clock;

    public PlayerState(Cooldowns clock) {
        this.clock = clock;
    }

    private State get(ServerPlayer player) {
        return states.computeIfAbsent(player.getUUID(), id -> new State());
    }

    public void grantFallImmunity(ServerPlayer player, int ticks) {
        get(player).fallImmuneUntil = clock.tick() + ticks;
    }

    public boolean isFallImmune(ServerPlayer player) {
        State state = states.get(player.getUUID());
        return state != null && state.fallImmuneUntil > clock.tick();
    }

    public void armMomentum(ServerPlayer player, int ticks) {
        get(player).momentumUntil = clock.tick() + ticks;
    }

    /** True once, then spent - a single dash arms exactly one bonus hit. */
    public boolean consumeMomentum(ServerPlayer player) {
        State state = states.get(player.getUUID());
        if (state == null || state.momentumUntil <= clock.tick()) {
            return false;
        }
        state.momentumUntil = 0;
        return true;
    }

    public String carrying(ServerPlayer player) {
        State state = states.get(player.getUUID());
        return state == null ? null : state.carrying;
    }

    public void setCarrying(ServerPlayer player, String weaponId) {
        get(player).carrying = weaponId;
    }

    public void markAirDashUsed(ServerPlayer player) {
        get(player).airDashUsed = true;
    }

    public boolean airDashUsed(ServerPlayer player) {
        State state = states.get(player.getUUID());
        return state != null && state.airDashUsed;
    }

    /** Touching the ground restores the air dash. Run from the server tick. */
    public void onTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State state = states.get(player.getUUID());
            if (state != null && state.airDashUsed && player.onGround()) {
                state.airDashUsed = false;
            }
        }
    }

    public void forget(UUID player) {
        states.remove(player);
    }

    public void clear() {
        states.clear();
    }
}
