package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.Hurt;
import dev.customweapons.ItemCost;
import dev.customweapons.Stats;
import dev.customweapons.Targeting;
import dev.customweapons.Weapons;
import dev.customweapons.WeaponsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Starfall - a mace that makes the wielder the meteor.
 *
 * <p>The vanilla mace already rewards falling onto people. The Comet is the launch: straight
 * up, then whatever the wielder lands on - or hits on the way down - takes the impact, and
 * the vanilla smash lands on top of it.
 */
public final class Starfall extends CustomWeapon {

    public static final String COMET = "comet";

    private static final class Falling {
        long until;
        boolean airborne;
    }

    private final Map<UUID, Falling> comets = new HashMap<>();

    @Override
    public String id() {
        return "starfall";
    }

    @Override
    public Item baseItem() {
        return Items.MACE;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.starfall_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Starfall")
                .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine("Comet: right-click to launch yourself into the sky"),
                Weapons.loreLine(String.format("Impact: landing, or your first hit on the way down, deals %.1f to everything within %.0f blocks",
                        config.impact_damage, config.impact_radius)),
                Weapons.loreLine(String.format("Cooldown %.0fs - the smash still lands on top", config.comet_cooldown_ticks / 20.0)),
                Weapons.loreLine("Heavy: every hit knocks further"));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.MACE, 1),
                new ItemCost(Items.MAGMA_BLOCK, 4),
                new ItemCost(Items.AMETHYST_SHARD, 3),
                new ItemCost(Items.NETHER_STAR, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.POLISHED_BLACKSTONE_BRICKS;
    }

    @Override
    public Block altarAccent() {
        return Blocks.AMETHYST_BLOCK;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.starfall_attack_damage, config.starfall_attack_speed);
    }

    @Override
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        if (!player.onGround()) {
            return InteractionResult.PASS;   // one launch per landing, and never a free double jump
        }
        int remaining = CustomWeapons.cooldowns().remaining(player, COMET);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(String.format("Comet  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        Vec3 velocity = new Vec3(0, config.comet_launch_velocity, 0);
        player.setDeltaMovement(velocity);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.hurtMarked = true;

        Falling falling = new Falling();
        falling.until = CustomWeapons.cooldowns().tick() + config.comet_window_ticks;
        falling.airborne = false;
        comets.put(player.getUUID(), falling);
        CustomWeapons.state().grantFallImmunity(player, config.comet_fall_immunity_ticks);
        CustomWeapons.cooldowns().set(player, COMET, config.comet_cooldown_ticks, weapon);
        CustomWeapons.animations().play(player, this, config);

        CustomWeapons.effects().play(level, "comet_launch", player.position(), null);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.0f, 0.6f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0f, 0.7f);
        level.sendParticles(ParticleTypes.LAVA, player.getX(), player.getY() + 0.2, player.getZ(), 20, 0.4, 0.1, 0.4, 0.0);
        level.sendParticles(ParticleTypes.GUST, player.getX(), player.getY(), player.getZ(), 10, 0.4, 0.1, 0.4, 0.05);
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY comet player={}", player.getName().getString());
        }
        player.displayClientMessage(Component.literal("Comet").withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return InteractionResult.SUCCESS;
    }

    /** Landing while falling as a comet is the impact, hit or no hit. */
    @Override
    public void onTick(MinecraftServer server, WeaponsConfig config) {
        if (comets.isEmpty()) {
            return;
        }
        long now = CustomWeapons.cooldowns().tick();
        // A snapshot: the impact below deals damage, and anything a damage or death event
        // does to `comets` while a live iterator is open would throw.
        for (var entry : java.util.List.copyOf(comets.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Falling falling = entry.getValue();
            if (comets.get(entry.getKey()) != falling) {
                continue;
            }
            if (player == null || now > falling.until || Weapons.of(player.getMainHandItem(), config) != this) {
                comets.remove(entry.getKey());    // it fizzled: they put the mace away, timed out, or left
                continue;
            }
            if (!player.onGround()) {
                falling.airborne = true;
                if (player.level() instanceof ServerLevel level && player.getDeltaMovement().y < -0.3) {
                    level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 0.5, player.getZ(),
                            3, 0.2, 0.3, 0.2, 0.01);
                }
                continue;
            }
            if (falling.airborne) {
                comets.remove(entry.getKey());
                if (player.level() instanceof ServerLevel level) {
                    impact(level, player, player.position(), null, config);
                }
            }
        }
    }

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        CustomWeapons.animations().play(attacker, this, config);
        ServerLevel level = attacker.level() instanceof ServerLevel l ? l : null;

        // Heavy: an extra shove along the swing.
        if (config.heavy_knockback > 0 && !CustomWeapons.stuns().isRigid(victim)) {
            Vec3 look = attacker.getLookAngle().multiply(1, 0, 1).normalize();
            victim.setDeltaMovement(victim.getDeltaMovement().add(look.x * config.heavy_knockback, 0.1,
                    look.z * config.heavy_knockback));
            victim.hurtMarked = true;
            if (victim instanceof ServerPlayer hit) {
                hit.connection.send(new ClientboundSetEntityMotionPacket(hit));
            }
        }

        Falling falling = comets.get(attacker.getUUID());
        if (falling != null && falling.airborne && level != null) {
            comets.remove(attacker.getUUID());
            impact(level, attacker, victim.position(), victim, config);
        }
    }

    private void impact(ServerLevel level, ServerPlayer player, Vec3 at, LivingEntity struck, WeaponsConfig config) {
        CustomWeapons.effects().play(level, "meteor_impact", at, null);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.0f, 0.7f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6f, 0.9f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.3, at.z, 30, 1.5, 0.3, 1.5, 0.0);
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 60, 2.0, 0.4, 2.0, 0.08);

        int hits = 0;
        for (LivingEntity victim : Targeting.around(level, player, at, config.impact_radius)) {
            Hurt.deal(victim, victim.damageSources().indirectMagic(player, player), (float) config.impact_damage);
            victim.igniteForSeconds(config.impact_fire_ticks / 20.0f);
            if (!CustomWeapons.stuns().isRigid(victim)) {
                Vec3 away = victim.position().subtract(at).multiply(1, 0, 1);
                if (away.lengthSqr() < 1.0e-4) {
                    away = new Vec3(0, 0, 1);
                }
                away = away.normalize().scale(config.impact_knock_out);
                victim.setDeltaMovement(victim.getDeltaMovement().add(away.x, config.impact_knock_up, away.z));
                victim.hurtMarked = true;
                if (victim instanceof ServerPlayer hit) {
                    hit.connection.send(new ClientboundSetEntityMotionPacket(hit));
                }
            }
            hits++;
        }
        player.resetFallDistance();
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY impact player={} hits={} at={} struck={}",
                    player.getName().getString(), hits,
                    String.format("%.1f,%.1f,%.1f", at.x, at.y, at.z),
                    struck == null ? "ground" : struck.getName().getString());
        }
        player.displayClientMessage(Component.literal("Impact  " + hits + " hit")
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    @Override
    public void forget(UUID player) {
        comets.remove(player);
    }
}
