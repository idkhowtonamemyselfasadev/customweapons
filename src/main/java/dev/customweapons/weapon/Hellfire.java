package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.ItemCost;
import dev.customweapons.Weapons;
import dev.customweapons.WeaponsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Hellfire - a crossbow whose bolts explode where they land.
 *
 * <p>The blast never breaks a block: it is a weapon, not a mining tool, and on an SMP a
 * ranged block-breaker is a griefing tool with extra steps. It does hurt the shooter if
 * they fire it at their own feet, the same as TNT would.
 *
 * <p>Vanilla gives no event for an arrow hitting a block, and the flag that says it is
 * stuck is not visible from outside the class. What is visible is that a stuck arrow stops
 * moving, so a bolt whose speed has dropped to nothing has landed. A crossbow fires at
 * 3.15 blocks a tick and an arrow in flight never comes near zero, so there is no
 * mistaking one for the other.
 */
public final class Hellfire extends CustomWeapon {

    public static final String BLAST = "blast";
    /** Below this a bolt is stuck in something. In flight it is above 1.0 until it lands. */
    private static final double LANDED_SPEED_SQR = 0.01;

    @Override
    public String id() {
        return "hellfire";
    }

    @Override
    public Item baseItem() {
        return Items.CROSSBOW;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.hellfire_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Hellfire")
                .withStyle(style -> style.withColor(ChatFormatting.RED)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine("Blast: the bolt explodes where it lands"),
                Weapons.loreLine("Breaks no blocks - and does not spare you"),
                Weapons.loreLine(String.format("Cooldown %.0fs - fires normally meanwhile",
                        config.hellfire_cooldown_ticks / 20.0)));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.CROSSBOW, 1),
                new ItemCost(Items.MAGMA_BLOCK, 3),
                new ItemCost(Items.BLAZE_ROD, 4),
                new ItemCost(Items.NETHER_STAR, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.NETHER_BRICKS;
    }

    @Override
    public Block altarAccent() {
        return Blocks.MAGMA_BLOCK;
    }

    @Override
    public void onArrowFired(ServerPlayer shooter, AbstractArrow arrow, ServerLevel level,
                             WeaponsConfig config) {
        if (arrow instanceof ThrownTrident) {
            return;
        }
        // Charged on the shot rather than the hit: the bolt explodes wherever it ends up,
        // so there is no such thing as a miss. Multishot fires three in one tick and only
        // the first is armed, so a volley is one blast, not three.
        if (!CustomWeapons.cooldowns().ready(shooter, BLAST)) {
            return;
        }
        CustomWeapons.cooldowns().set(shooter, BLAST, config.hellfire_cooldown_ticks);
        CustomWeapons.projectiles().arm(this, shooter, arrow);
        CustomWeapons.animations().play(shooter, this, config);
        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0f, 0.7f);
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY hellfire arm player={}", shooter.getName().getString());
        }
        shooter.displayClientMessage(Component.literal("Hellfire")
                .withStyle(ChatFormatting.RED), true);
    }

    @Override
    public boolean onProjectileTick(ServerPlayer shooter, AbstractArrow projectile, ServerLevel level,
                                    WeaponsConfig config) {
        level.sendParticles(ParticleTypes.FLAME, projectile.getX(), projectile.getY(), projectile.getZ(),
                2, 0.05, 0.05, 0.05, 0.01);
        if (projectile.getDeltaMovement().lengthSqr() > LANDED_SPEED_SQR) {
            return true;
        }
        explode(projectile, projectile.position(), null, config);
        return false;
    }

    @Override
    public void onProjectileHit(ServerPlayer shooter, LivingEntity victim, AbstractArrow projectile,
                                WeaponsConfig config) {
        // The bolt's own hit just gave the target invulnerability frames, which would
        // swallow most of the blast on the one entity it was aimed at.
        victim.invulnerableTime = 0;
        explode(projectile, victim.position().add(0, victim.getBbHeight() * 0.5, 0), victim, config);
    }

    private void explode(AbstractArrow projectile, Vec3 at, LivingEntity direct, WeaponsConfig config) {
        if (!(projectile.level() instanceof ServerLevel level)) {
            return;
        }
        // The projectile as the source credits its owner for every kill, the way a
        // creeper's or a fireball's explosion credits the creeper or the ghast.
        level.explode(projectile, at.x, at.y, at.z, (float) config.hellfire_explosion_power,
                config.hellfire_fire, Level.ExplosionInteraction.NONE);
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 12, 0.3, 0.3, 0.3, 0.1);
        projectile.discard();
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY hellfire explode direct={} at={}",
                    direct == null ? "ground" : direct.getName().getString(),
                    String.format("%.1f %.1f %.1f", at.x, at.y, at.z));
        }
    }
}
