package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.Hurt;
import dev.customweapons.ItemCost;
import dev.customweapons.Stats;
import dev.customweapons.Weapons;
import dev.customweapons.WeaponsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Tidecaller - a trident that harpoons: a thrown hit drags the target to you.
 *
 * <p>Thrown or swung, a target standing in water or rain takes extra. The throw itself is
 * vanilla's, Loyalty and Riptide included; only the hit is hooked, so nothing about how the
 * trident flies or returns changes.
 */
public final class Tidecaller extends CustomWeapon {

    public static final String HARPOON = "harpoon";

    @Override
    public String id() {
        return "tidecaller";
    }

    @Override
    public Item baseItem() {
        return Items.TRIDENT;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.tidecaller_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Tidecaller")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_AQUA)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine("Harpoon: a thrown hit drags the target to you"),
                Weapons.loreLine(String.format("Cooldown %.0fs - throws normally meanwhile",
                        config.harpoon_cooldown_ticks / 20.0)),
                Weapons.loreLine(String.format("+%.1f against anything in water or rain",
                        config.tide_wet_bonus_damage)));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.TRIDENT, 1),
                new ItemCost(Items.SEA_LANTERN, 3),
                new ItemCost(Items.NAUTILUS_SHELL, 4),
                new ItemCost(Items.CONDUIT, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.DARK_PRISMARINE;
    }

    @Override
    public Block altarAccent() {
        return Blocks.SEA_LANTERN;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.tidecaller_attack_damage, config.tidecaller_attack_speed);
    }

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        wetBonus(attacker, victim, config);
    }

    private void wetBonus(ServerPlayer attacker, LivingEntity victim, WeaponsConfig config) {
        if (!victim.isInWaterOrRain() || config.tide_wet_bonus_damage <= 0) {
            return;
        }
        Hurt.deal(victim, victim.damageSources().indirectMagic(attacker, attacker),
                (float) config.tide_wet_bonus_damage);
        if (victim.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SPLASH,
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(),
                    20, 0.3, 0.3, 0.3, 0.1);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY tide player={} victim={} bonus={}",
                    attacker.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", config.tide_wet_bonus_damage));
        }
    }

    @Override
    public void onArrowFired(ServerPlayer shooter, AbstractArrow arrow, ServerLevel level,
                             WeaponsConfig config) {
        if (!(arrow instanceof ThrownTrident)) {
            return;
        }
        // Armed even on cooldown, so the wet bonus still lands; the harpoon checks again.
        CustomWeapons.projectiles().arm(this, shooter, arrow);
    }

    @Override
    public void onProjectileHit(ServerPlayer shooter, LivingEntity victim, AbstractArrow projectile,
                                WeaponsConfig config) {
        if (shooter == null) {
            return;
        }
        wetBonus(shooter, victim, config);
        int remaining = CustomWeapons.cooldowns().remaining(shooter, HARPOON);
        if (remaining > 0) {
            shooter.displayClientMessage(Component.literal(
                            String.format("Harpoon  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!victim.isAlive()) {
            return;
        }

        Vec3 toShooter = shooter.position().subtract(victim.position());
        double distance = toShooter.length();
        if (distance < 1.0e-3) {
            return;
        }
        Vec3 pull = toShooter.normalize().scale(config.harpoon_pull_power);
        victim.setDeltaMovement(pull.x, pull.y + config.harpoon_pull_lift, pull.z);
        victim.hurtMarked = true;
        if (victim instanceof ServerPlayer hit) {
            hit.connection.send(new ClientboundSetEntityMotionPacket(hit));
        }
        CustomWeapons.cooldowns().set(shooter, HARPOON, config.harpoon_cooldown_ticks);

        if (victim.level() instanceof ServerLevel level) {
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0f, 0.6f);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0f, 0.8f);
            Vec3 from = victim.position().add(0, victim.getBbHeight() * 0.5, 0);
            Vec3 to = shooter.position().add(0, shooter.getBbHeight() * 0.5, 0);
            int steps = (int) Math.min(40, Math.max(6, distance * 2));
            for (int i = 0; i <= steps; i++) {
                Vec3 point = from.lerp(to, i / (double) steps);
                level.sendParticles(ParticleTypes.BUBBLE_POP, point.x, point.y, point.z,
                        1, 0.05, 0.05, 0.05, 0.0);
            }
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY harpoon player={} victim={} distance={}",
                    shooter.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", distance));
        }
        shooter.displayClientMessage(Component.literal("Harpoon")
                .withStyle(ChatFormatting.DARK_AQUA), true);
    }
}
