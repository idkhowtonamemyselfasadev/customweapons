package dev.customweapons;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a player is looking at.
 *
 * <p>Shared by every aimed ability (the ice beam, the sunstrike, the rift): a ray from the
 * eyes along the look direction, stopped by the first block, and the nearest living thing
 * whose (slightly inflated) hitbox the ray passes through before that block.
 */
public final class Targeting {

    /**
     * @param from   the eyes
     * @param end    where the ray stopped: the wall it hit, or its full range
     * @param target the first living thing along it, or null
     * @param wall   true if a block stopped the ray short of its range
     */
    public record Ray(Vec3 from, Vec3 end, LivingEntity target, boolean wall) {
        /** The point the ray "lands" on: the target's centre if there is one, else the end. */
        public Vec3 point() {
            return target == null ? end : target.position().add(0, target.getBbHeight() * 0.5, 0);
        }

        /** Where the ray meets the ground: the target's feet, or the block it hit. */
        public Vec3 ground() {
            return target == null ? end : target.position();
        }
    }

    private Targeting() {
    }

    public static Ray look(ServerPlayer player, ServerLevel level, double range) {
        Vec3 from = player.getEyePosition();
        Vec3 dir = player.getLookAngle().normalize();
        Vec3 end = from.add(dir.scale(range));
        BlockHitResult wall = level.clip(new ClipContext(from, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean hitWall = wall.getType() != HitResult.Type.MISS;
        if (hitWall) {
            end = wall.getLocation();
        }
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        AABB sweep = new AABB(from, end).inflate(1.0);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, sweep,
                e -> e != player && e.isAlive() && !(e instanceof Player p && (p.isCreative() || p.isSpectator())))) {
            java.util.Optional<Vec3> hit = candidate.getBoundingBox().inflate(0.35).clip(from, end);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(from);
                if (d < best) {
                    best = d;
                    target = candidate;
                }
            }
        }
        return new Ray(from, end, target, hitWall);
    }

    /** Everything living within {@code radius} of a point, never the player themselves. */
    public static java.util.List<LivingEntity> around(ServerLevel level, ServerPlayer player, Vec3 at, double radius) {
        AABB box = new AABB(at, at).inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box, e ->
                e != player && e.isAlive()
                        && !(e instanceof Player p && (p.isCreative() || p.isSpectator()))
                        && e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(at) <= radius + e.getBbWidth() * 0.5);
    }
}
