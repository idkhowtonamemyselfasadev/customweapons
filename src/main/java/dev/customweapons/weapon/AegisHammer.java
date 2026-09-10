package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.Hurt;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.customweapons.ItemCost;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Aegis Hammer - a slow netherite axe that slams the ground.
 *
 * <p>Built on an axe, not a mace, on purpose. A mace's fall-distance smash would stack on top
 * of the slam and blow straight past the balance ceiling. The axe also keeps the vanilla
 * shield disable, which suits the weapon.
 */
public final class AegisHammer extends CustomWeapon {

    public static final String SLAM = "slam";

    @Override
    public String id() {
        return "aegis_hammer";
    }

    @Override
    public Item baseItem() {
        return Items.NETHERITE_AXE;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.aegis_hammer_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Aegis Hammer")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine("Ground Slam: right-click on the ground"),
                Weapons.loreLine(String.format("%.1f damage and Slowness %s within %.0f blocks",
                        config.slam_damage,
                        config.slam_slowness_amplifier >= 1 ? "II" : "I",
                        config.slam_radius)),
                Weapons.loreLine(String.format("Resistance for %.0fs to you",
                        config.slam_resistance_ticks / 20.0)),
                Weapons.loreLine(String.format("Cooldown %.0fs",
                        config.slam_cooldown_ticks / 20.0)));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.NETHERITE_AXE, 1),
                new ItemCost(Items.ECHO_SHARD, 3),
                new ItemCost(Items.CRYING_OBSIDIAN, 4),
                new ItemCost(Items.TOTEM_OF_UNDYING, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.POLISHED_BLACKSTONE_BRICKS;
    }

    @Override
    public Block altarAccent() {
        return Blocks.GILDED_BLACKSTONE;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.aegis_hammer_attack_damage, config.aegis_hammer_attack_speed);
    }

    @Override
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        if (!player.onGround()) {
            // Airborne does nothing and costs nothing - a ground slam in mid-air would also
            // be a free escape from the cooldown.
            return InteractionResult.PASS;
        }
        int remaining = CustomWeapons.cooldowns().remaining(player, SLAM);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(
                            String.format("Ground Slam  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }

        AABB box = player.getBoundingBox().inflate(config.slam_radius);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box, entity ->
                entity != player
                        && entity.isAlive()
                        && !(entity instanceof Player other && (other.isCreative() || other.isSpectator()))
                        && entity.distanceTo(player) <= config.slam_radius
                        // A slam through a wall would hit people who never saw it coming.
                        && player.hasLineOfSight(entity));

        for (LivingEntity target : targets) {
            Hurt.deal(target, target.damageSources().playerAttack(player), (float) config.slam_damage);
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                    config.slam_slowness_ticks, config.slam_slowness_amplifier));

            Vec3 away = target.position().subtract(player.position());
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(0, 0, 1);
            }
            away = away.normalize().scale(config.slam_knock_out);
            target.setDeltaMovement(target.getDeltaMovement()
                    .add(away.x, config.slam_knock_up, away.z));
            target.hurtMarked = true;
            if (target instanceof ServerPlayer hit) {
                hit.connection.send(new ClientboundSetEntityMotionPacket(hit));
            }
        }

        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, config.slam_resistance_ticks, 0));
        CustomWeapons.cooldowns().set(player, SLAM, config.slam_cooldown_ticks, weapon);
        CustomWeapons.animations().play(player, this, config);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 1.0f, 0.8f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.3f, 0.8f);
        ring(level, player, config.slam_radius);

        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY slam player={} targets={} radius={}",
                    player.getName().getString(), targets.size(),
                    String.format("%.1f", config.slam_radius));
        }
        player.displayClientMessage(Component.literal("Ground Slam  " + targets.size() + " hit")
                .withStyle(ChatFormatting.GOLD), true);
        return InteractionResult.SUCCESS;
    }

    /** Draws the slam radius so everyone can see exactly how far it reached. */
    private void ring(ServerLevel level, ServerPlayer player, double radius) {
        int points = 30;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2 * i) / points;
            double x = player.getX() + Math.cos(angle) * radius;
            double z = player.getZ() + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, x, player.getY() + 0.1, z,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, player.getX(), player.getY() + 0.2, player.getZ(),
                1, 0.0, 0.0, 0.0, 0.0);
    }
}
