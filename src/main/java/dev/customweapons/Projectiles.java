package dev.customweapons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Projectiles that are carrying a weapon's ability: the Tidecaller's harpoon throw and the
 * Hellfire's bolt.
 *
 * <p>A weapon arms a projectile at the moment it is fired. From then on the projectile is
 * ticked here, and the hit is routed back to the weapon from the damage event, so the
 * weapon classes never need a mixin on the projectile. The Stormpiercer predates this and
 * keeps its own {@link ShockManager}; the two do the same job.
 */
public final class Projectiles {

    /** Long enough for anything still flying; nothing accumulates from shots that vanished. */
    private static final int TTL_TICKS = 600;

    private record Armed(CustomWeapon weapon, AbstractArrow projectile, UUID shooter, long since) {
    }

    private final Map<UUID, Armed> armed = new HashMap<>();
    private final Cooldowns clock;

    public Projectiles(Cooldowns clock) {
        this.clock = clock;
    }

    public void arm(CustomWeapon weapon, ServerPlayer shooter, AbstractArrow projectile) {
        armed.put(projectile.getUUID(), new Armed(weapon, projectile, shooter.getUUID(), clock.tick()));
    }

    /** The weapon that armed this projectile, removed so a hit fires once. Null if none. */
    public CustomWeapon take(AbstractArrow projectile) {
        Armed entry = armed.remove(projectile.getUUID());
        return entry == null ? null : entry.weapon();
    }

    public void onTick(MinecraftServer server, WeaponsConfig config) {
        if (armed.isEmpty()) {
            return;
        }
        long cutoff = clock.tick() - TTL_TICKS;
        // A snapshot, never the live map: a bolt that detonates on the ground in its tick
        // hurts whatever stands next to it, that damage event calls take() on this same map
        // from inside the loop, and the iterator's own remove() then threw a
        // ConcurrentModificationException that took the server tick loop down with it.
        for (Armed entry : java.util.List.copyOf(armed.values())) {
            AbstractArrow projectile = entry.projectile();
            UUID id = projectile.getUUID();
            if (entry.since() < cutoff || projectile.isRemoved()
                    || !(projectile.level() instanceof ServerLevel level)) {
                armed.remove(id);
                continue;
            }
            if (!armed.containsKey(id)) {
                continue;   // taken by a hit earlier in this same tick
            }
            ServerPlayer shooter = server.getPlayerList().getPlayer(entry.shooter());
            if (!entry.weapon().onProjectileTick(shooter, projectile, level, config)) {
                armed.remove(id);
            }
        }
    }

    public void clear() {
        armed.clear();
    }
}
