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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.customweapons.ItemCost;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.Vec3;

import dev.customweapons.Ultimate;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import java.util.List;

/**
 * Gale Edge - a fast diamond sword that dashes.
 *
 * <p>The dash is what you pay for. It is still only a diamond sword: no netherite tier, so it
 * burns durability faster and dies in lava, and 5.95 a hit is under a plain diamond sword.
 */
public final class GaleEdge extends CustomWeapon {

    public static final String DASH = "dash";

    @Override
    public String id() {
        return "gale_edge";
    }

    @Override
    public Item baseItem() {
        return Items.DIAMOND_SWORD;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.gale_edge_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Gale Edge")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine("Dash: right-click to launch forward"),
                Weapons.loreLine(String.format("No fall damage for %.0fs afterwards",
                        config.dash_fall_immunity_ticks / 20.0)),
                Weapons.loreLine(String.format("Next hit within %.0fs deals +%.1f",
                        config.momentum_window_ticks / 20.0, config.momentum_bonus_damage)),
                Weapons.loreLine(String.format("Cooldown %.0fs - one dash per airtime",
                        config.dash_cooldown_ticks / 20.0)));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.DIAMOND_SWORD, 1),
                new ItemCost(Items.SHULKER_SHELL, 3),
                new ItemCost(Items.BREEZE_ROD, 4),
                new ItemCost(Items.HEAVY_CORE, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.CALCITE;
    }

    @Override
    public Block altarAccent() {
        return Blocks.PACKED_ICE;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.gale_edge_attack_damage, config.gale_edge_attack_speed);
    }

    @Override
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        // A dash that cannot happen must not eat the cooldown - being told "8 seconds" for
        // an input that did nothing is worse than the input doing nothing.
        if (!player.onGround() && CustomWeapons.state().airDashUsed(player)) {
            return InteractionResult.PASS;
        }
        int remaining = CustomWeapons.cooldowns().remaining(player, DASH);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(
                            String.format("Dash  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }

        Vec3 look = player.getLookAngle().normalize();
        double y = Math.min(Math.max(look.y * config.dash_power, config.dash_min_y), config.dash_max_y);
        Vec3 velocity = new Vec3(look.x * config.dash_power, y, look.z * config.dash_power);
        player.setDeltaMovement(velocity);
        // A player's own movement is client-authoritative; without this packet the server
        // sets a velocity the client immediately overwrites and nothing visible happens.
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.hurtMarked = true;

        if (!player.onGround()) {
            CustomWeapons.state().markAirDashUsed(player);
        }
        CustomWeapons.state().grantFallImmunity(player, config.dash_fall_immunity_ticks);
        CustomWeapons.state().armMomentum(player, config.momentum_window_ticks);
        // Whoever stands in the dash's path takes the impact (the dash alone did no damage).
        if (config.dash_impact_damage > 0 && player.level() instanceof ServerLevel lvl) {
            Vec3 from = player.getEyePosition();
            Vec3 to = from.add(look.scale(6));
            for (LivingEntity other : dev.customweapons.Targeting.around(lvl, player, from.add(look.scale(3)), 3.5)) {
                // Within a block of the dash line.
                Vec3 p = other.position().add(0, other.getBbHeight() * 0.5, 0);
                double t = Math.max(0, Math.min(1, p.subtract(from).dot(to.subtract(from)) / to.subtract(from).lengthSqr()));
                if (p.distanceTo(from.add(to.subtract(from).scale(t))) <= 1.2) {
                    dev.customweapons.Ultimate.strike(player, other, config.dash_impact_damage);
                    CustomWeapons.effects().play(lvl, "wind_hit", other);
                }
            }
        }
        CustomWeapons.cooldowns().set(player, DASH, config.dash_cooldown_ticks, weapon);
        CustomWeapons.animations().play(player, this, config);

        if (player.level() instanceof ServerLevel level) {
            CustomWeapons.effects().play(level, "wind_dash", player.position(), null);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.PLAYERS, 1.0f, 1.2f);
            level.sendParticles(ParticleTypes.GUST, player.getX(), player.getY(), player.getZ(),
                    20, 0.4, 0.1, 0.4, 0.05);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY dash player={} velocity={}",
                    player.getName().getString(),
                    String.format("%.2f", velocity.length()));
        }
        player.displayClientMessage(Component.literal("Dash")
                .withStyle(ChatFormatting.AQUA), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        if (!CustomWeapons.state().consumeMomentum(attacker)) {
            return;
        }
        // Dealt as a separate magic hit rather than by inflating the swing: the swing has
        // already resolved by the time this runs, and magic does not knock back twice.
        Hurt.deal(victim, victim.damageSources().indirectMagic(attacker, attacker),
                (float) config.momentum_bonus_damage);
        if (attacker.level() instanceof ServerLevel level) {
            CustomWeapons.effects().play(level, "wind_hit", victim);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0f, 1.4f);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY momentum player={} victim={} bonus={}",
                    attacker.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", config.momentum_bonus_damage));
        }
        attacker.displayClientMessage(Component.literal(
                        String.format("Momentum Strike  +%.1f", config.momentum_bonus_damage))
                .withStyle(ChatFormatting.AQUA), true);
    }

    // ---- ultimate: Tempest ----------------------------------------------------------------------
    public static final String TEMPEST = "tempest";

    @Override
    public void onUltimate(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        if (!Ultimate.ready(player, TEMPEST, "Tempest")) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Ultimate.spin(player, 16);
        int hit = 0;
        for (LivingEntity victim : Ultimate.targets(player, config.tempest_radius)) {
            if (Ultimate.strike(player, victim, config.tempest_damage)) {
                hit++;
            }
            Ultimate.fling(player, victim, config.tempest_fling, config.tempest_lift);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
            CustomWeapons.effects().play(level, "wind_hit", victim);
        }
        level.sendParticles(ParticleTypes.GUST, player.getX(), player.getY() + 1, player.getZ(), 60, 2.5, 1, 2.5, 0.1);
        CustomWeapons.state().grantFallImmunity(player, 100);
        Ultimate.fired(player, this, TEMPEST, config.tempest_cooldown_ticks, "Tempest", hit, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 0.7f, config);
    }

    @Override
    public String ultimateLore(WeaponsConfig config) {
        return String.format("Tempest - flings everything within %.0f blocks away for %.1f true damage. %ds", config.tempest_radius, config.tempest_damage, config.tempest_cooldown_ticks / 20);
    }
}
