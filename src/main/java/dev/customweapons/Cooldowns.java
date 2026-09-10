package dev.customweapons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player, per-ability cooldowns on the server tick clock.
 *
 * <p>Where the ability is a right-click the vanilla item cooldown is set as well, so the
 * client draws the sweep overlay on the hotbar slot with no client-side code. That is not
 * possible for the Stormpiercer: a vanilla item cooldown on a bow would stop the bow being
 * fired at all, and the Stormpiercer is supposed to keep shooting normal arrows while its
 * shock recharges. It gets the action bar only.
 */
public final class Cooldowns {

    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    private long tick;

    public void onTick(MinecraftServer server) {
        tick++;
    }

    public long tick() {
        return tick;
    }

    public boolean ready(ServerPlayer player, String key) {
        return remaining(player, key) <= 0;
    }

    /** Ticks left, or 0 when the ability is ready. */
    public int remaining(ServerPlayer player, String key) {
        Map<String, Long> byKey = readyAt.get(player.getUUID());
        if (byKey == null) {
            return 0;
        }
        Long ready = byKey.get(key);
        if (ready == null) {
            return 0;
        }
        long left = ready - tick;
        return left <= 0 ? 0 : (int) left;
    }

    public void set(ServerPlayer player, String key, int ticks) {
        readyAt.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).put(key, tick + ticks);
    }

    /** Sets the cooldown and shows it on the hotbar slot the vanilla way. */
    public void set(ServerPlayer player, String key, int ticks, ItemStack stack) {
        set(player, key, ticks);
        player.getCooldowns().addCooldown(stack, ticks);
    }

    public void forget(UUID player) {
        readyAt.remove(player);
    }

    /** Cooldowns are session state; a restart clears them rather than persisting them. */
    public void clear() {
        readyAt.clear();
    }
}
