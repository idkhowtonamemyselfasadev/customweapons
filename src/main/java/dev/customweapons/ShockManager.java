package dev.customweapons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Stormpiercer's shock.
 *
 * <p>An arrow is armed at the moment it is fired, if the bow was drawn far enough. Draw
 * strength is read off the arrow's launch speed: a bow fires at {@code charge * 3.0} blocks
 * a tick, so a speed of 2.7 is a draw of 0.9. That avoids a mixin on the bow item entirely,
 * which matters because this mod has to keep working on a vanilla client.
 *
 * <p>The cooldown is charged on the hit, not on the shot, so a miss costs nothing.
 */
public final class ShockManager {

    public static final String KEY = "shock";
    /** Long enough for any arrow still in flight; short enough that nothing accumulates. */
    private static final int ARROW_TTL_TICKS = 600;

    private final Map<UUID, Long> armedArrows = new HashMap<>();
    private final Cooldowns cooldowns;

    public ShockManager(Cooldowns cooldowns) {
        this.cooldowns = cooldowns;
    }

    public void arm(AbstractArrow arrow, WeaponsConfig config) {
        armedArrows.put(arrow.getUUID(), cooldowns.tick());
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY shock arm speed={}",
                    String.format("%.2f", arrow.getDeltaMovement().length()));
        }
    }

    public boolean isArmed(AbstractArrow arrow) {
        return armedArrows.containsKey(arrow.getUUID());
    }

    /**
     * Applies the shock to an entity an armed arrow has just hit.
     *
     * @return the extra damage dealt, for logging
     */
    public double onArrowHit(LivingEntity victim, AbstractArrow arrow, WeaponsConfig config) {
        if (armedArrows.remove(arrow.getUUID()) == null) {
            return 0;
        }
        if (!(victim.level() instanceof ServerLevel level)) {
            return 0;
        }
        ServerPlayer shooter = arrow.getOwner() instanceof ServerPlayer p ? p : null;

        boolean executed = isInstakill(victim, config);
        // An outright kill is dealt as a very large magic hit rather than by setting health
        // to zero, so it goes through the normal death path: the shooter is credited, loot
        // and experience drop, and Totems of Undying still work on a player.
        float damage = executed
                ? Math.max(1000.0f, victim.getMaxHealth() * 10.0f)
                : (float) config.shock_bonus_damage;
        Hurt.deal(victim, shooter != null
                ? victim.damageSources().indirectMagic(arrow, shooter)
                : victim.damageSources().magic(), damage);
        if (victim.isAlive()) {
            victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, config.shock_glowing_ticks, 0));
        }
        if (config.shock_lightning) {
            strike(level, victim, shooter, config);
        }
        if (victim.isAlive()) {
            CustomWeapons.effects().play(level, "storm_cage", victim);
        }

        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.4f, 1.6f);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
                15, 0.3, 0.4, 0.3, 0.05);

        double dealt = damage;
        LivingEntity chained = nearest(level, victim, shooter, config.shock_chain_range);
        if (chained != null) {
            Hurt.deal(chained, shooter != null
                    ? chained.damageSources().indirectMagic(arrow, shooter)
                    : chained.damageSources().magic(), (float) config.shock_chain_damage);
            drawArc(level, victim.position().add(0, victim.getBbHeight() * 0.6, 0),
                    chained.position().add(0, chained.getBbHeight() * 0.6, 0));
            dealt += config.shock_chain_damage;
        }

        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY shock hit victim={} chained={} total={} executed={}",
                    victim.getName().getString(),
                    chained == null ? "none" : chained.getName().getString(),
                    String.format("%.1f", dealt), executed);
        }
        if (shooter != null) {
            cooldowns.set(shooter, KEY, config.shock_cooldown_ticks);
            String label = executed ? "Shock  (executed)" : chained != null ? "Shock  (chained)" : "Shock";
            shooter.displayClientMessage(Component.literal(label)
                    .withStyle(ChatFormatting.AQUA), true);
        }
        return dealt;
    }

    /** Matches the victim's type against the configured id list, namespace optional. */
    private boolean isInstakill(LivingEntity victim, WeaponsConfig config) {
        if (config.shock_instakill == null || config.shock_instakill.isEmpty()) {
            return false;
        }
        if (victim instanceof Player) {
            return false;   // a list of mob ids must never become a one-shot on players
        }
        String type = EntityType.getKey(victim.getType()).toString();
        for (String id : config.shock_instakill) {
            String wanted = id.contains(":") ? id : "minecraft:" + id;
            if (wanted.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Brings a lightning bolt down on the target.
     *
     * <p>Visual only by default: flash, thunder and the strike, but no fire, no charged
     * creeper and no villager turned witch. The shock's own damage is what hurts. With
     * {@code shock_lightning_fire} on it is a real bolt and does all of that.
     */
    private void strike(ServerLevel level, LivingEntity victim, ServerPlayer shooter, WeaponsConfig config) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) {
            return;
        }
        bolt.setPos(victim.getX(), victim.getY(), victim.getZ());
        bolt.setVisualOnly(!config.shock_lightning_fire);
        if (shooter != null) {
            bolt.setCause(shooter);
        }
        level.addFreshEntity(bolt);
    }

    /** Never the shooter, never the entity already hit, never something already dead. */
    private LivingEntity nearest(ServerLevel level, LivingEntity target, ServerPlayer shooter, double range) {
        AABB box = target.getBoundingBox().inflate(range);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box, entity ->
                entity != target
                        && entity != shooter
                        && entity.isAlive()
                        && !(entity instanceof Player player && (player.isCreative() || player.isSpectator()))
                        && entity.distanceTo(target) <= range);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            double distance = candidate.distanceToSqr(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private void drawArc(ServerLevel level, Vec3 from, Vec3 to) {
        int steps = 12;
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.lerp(to, i / (double) steps);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z,
                    1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /** Arrows that never hit anything would otherwise sit in the map forever. */
    public void onTick() {
        if (armedArrows.isEmpty()) {
            return;
        }
        long cutoff = cooldowns.tick() - ARROW_TTL_TICKS;
        Iterator<Map.Entry<UUID, Long>> it = armedArrows.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
            }
        }
    }

    public void clear() {
        armedArrows.clear();
    }
}
