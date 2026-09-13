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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import dev.customweapons.Ultimate;
import java.util.List;

/**
 * Frostbrand - an iron sword that freezes what it cuts and shatters it once it is solid.
 *
 * <p>The frost is vanilla's own powder-snow freeze counter, so the client already knows
 * how to draw it: the frost creeps in from the edges of the screen for a player, and a
 * mob shivers. It thaws on its own at two ticks a tick, so the stacks are a matter of
 * hitting faster than the target can thaw. There is no per-victim state to keep here and
 * nothing to clean up: the counter lives on the entity.
 */
public final class Frostbrand extends CustomWeapon {
    private static final String SHATTER = "frostbrand_shatter";

    @Override
    public String id() {
        return "frostbrand";
    }

    @Override
    public Item baseItem() {
        return Items.IRON_SWORD;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.frostbrand_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Frostbrand")
                .withStyle(style -> style.withColor(ChatFormatting.WHITE)
                        .withBold(true).withItalic(false));
    }

    public static final String BEAM = "frostbeam";

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine(String.format("Ice Beam: right-click to freeze whatever is %.0f blocks ahead",
                        config.frost_beam_range)),
                Weapons.loreLine(String.format("It takes %.1f and is held in ice for %.1fs, cooldown %.0fs",
                        config.frost_beam_damage, config.frost_beam_freeze_ticks / 20.0,
                        config.frost_beam_cooldown_ticks / 20.0)),
                Weapons.loreLine(String.format("Frost: every hit chills and slows for %.0fs",
                        config.frost_slowness_ticks / 20.0)),
                Weapons.loreLine("Three quick hits freeze the target solid"),
                Weapons.loreLine(String.format("Shatter: a frozen target takes +%.1f and is frozen solid for %.0fs",
                        config.shatter_damage, config.shatter_slowness_ticks / 20.0)),
                Weapons.loreLine(String.format("Cooldown %.0fs after a shatter - the frost thaws if you stop swinging",
                        config.shatter_cooldown_ticks / 20.0)));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.IRON_SWORD, 1),
                new ItemCost(Items.PRISMARINE_CRYSTALS, 3),
                new ItemCost(Items.BLUE_ICE, 4),
                new ItemCost(Items.ENCHANTED_GOLDEN_APPLE, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.BLUE_ICE;
    }

    @Override
    public Block altarAccent() {
        return Blocks.PRISMARINE;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.frostbrand_attack_damage, config.frostbrand_attack_speed);
    }

    @Override
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        int remaining = CustomWeapons.cooldowns().remaining(player, BEAM);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(String.format("Ice Beam  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        Vec3 from = player.getEyePosition();
        Vec3 dir = player.getLookAngle().normalize();
        Vec3 end = from.add(dir.scale(config.frost_beam_range));
        // Stop at the first block in the way, then look for the first living thing along the ray.
        net.minecraft.world.phys.BlockHitResult wall = level.clip(new net.minecraft.world.level.ClipContext(from, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            end = wall.getLocation();
        }
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        AABB sweep = new AABB(from, end).inflate(1.0);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, sweep,
                e -> e != player && e.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player p && (p.isCreative() || p.isSpectator())))) {
            java.util.Optional<Vec3> hit = candidate.getBoundingBox().inflate(0.35).clip(from, end);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(from);
                if (d < best) {
                    best = d;
                    target = candidate;
                }
            }
        }
        Vec3 reach = target == null ? end : target.position().add(0, target.getBbHeight() * 0.5, 0);
        CustomWeapons.cooldowns().set(player, BEAM, config.frost_beam_cooldown_ticks, weapon);

        // The ray itself: a line of ice shards from the hand, and a trail of snow on the air.
        CustomWeapons.effects().beam(level, from.add(dir.scale(1.0)).subtract(0, 0.3, 0), reach,
                "minecraft:packed_ice", (int) Math.min(24, Math.max(6, from.distanceTo(reach) * 1.2)), 0.22f, 12);
        double length = from.distanceTo(reach);
        for (double t = 1; t < length; t += 0.8) {
            Vec3 p = from.add(dir.scale(t));
            level.sendParticles(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 2, 0.08, 0.08, 0.08, 0.01);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 1.0f, 0.6f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GLASS_PLACE, SoundSource.PLAYERS, 1.0f, 1.6f);

        if (target == null) {
            if (config.log_abilities) {
                CustomWeapons.LOGGER.info("ABILITY frostbeam player={} victim=miss", player.getName().getString());
            }
            player.displayClientMessage(Component.literal("Ice Beam").withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.SUCCESS;
        }
        // Frozen solid where it stands: the ice closes around it, it is held, and it takes the hit.
        LivingEntity victim = target;
        Hurt.deal(victim, victim.damageSources().indirectMagic(player, player), (float) config.frost_beam_damage);
        victim.setTicksFrozen(victim.getTicksRequiredToFreeze() + config.frost_beam_freeze_ticks);
        CustomWeapons.stuns().stun(victim, config.frost_beam_freeze_ticks, "Frozen", true);
        CustomWeapons.effects().play(level, "frost_beam", victim);
        CustomWeapons.effects().later(61, () -> {
            if (!victim.isRemoved()) {
                level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                                ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()),
                        victim.getX(), victim.getY() + 1.0, victim.getZ(), 80, 0.5, 0.9, 0.5, 0.2);
                level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                        SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 0.9f);
            }
        });
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 1.0f, 1.0f);
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY frostbeam player={} victim={} damage={}",
                    player.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", config.frost_beam_damage));
        }
        CustomWeapons.animations().play(player, this, config);
        player.displayClientMessage(Component.literal("Ice Beam  frozen ")
                .append(victim.getName()).withStyle(ChatFormatting.AQUA), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        CustomWeapons.animations().play(attacker, this, config);
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                config.frost_slowness_ticks, config.frost_slowness_amplifier));
        // After a shatter the blade needs a moment before the frost builds again: hits still
        // chill and slow, but the freeze counter stays where the thaw leaves it.
        int cooldown = CustomWeapons.cooldowns().remaining(attacker, SHATTER);
        if (cooldown > 0) {
            attacker.displayClientMessage(Component.literal(
                            String.format("Frost  %.1fs", cooldown / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int required = victim.getTicksRequiredToFreeze();
        int frozen = victim.getTicksFrozen() + config.frost_ticks_per_hit;

        if (frozen < required) {
            victim.setTicksFrozen(frozen);
            if (victim.level() instanceof ServerLevel level) {
                CustomWeapons.effects().play(level, "frost_hit", victim);
                level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                        SoundEvents.POWDER_SNOW_HIT, SoundSource.PLAYERS, 1.0f, 0.8f);
                level.sendParticles(ParticleTypes.SNOWFLAKE,
                        victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(),
                        12, 0.3, 0.4, 0.3, 0.02);
            }
            if (config.log_abilities) {
                CustomWeapons.LOGGER.info("ABILITY frost player={} victim={} frozen={}/{}",
                        attacker.getName().getString(), victim.getName().getString(), frozen, required);
            }
            attacker.displayClientMessage(Component.literal(
                            String.format("Frost  %d%%", Math.min(100, frozen * 100 / required)))
                    .withStyle(ChatFormatting.WHITE), true);
            return;
        }

        // Frozen solid: the shatter. Back to zero afterwards, so the next three hits are a
        // new build-up rather than a shatter every swing.
        victim.setTicksFrozen(0);
        CustomWeapons.cooldowns().set(attacker, SHATTER, config.shatter_cooldown_ticks, weapon);
        Hurt.deal(victim, victim.damageSources().indirectMagic(attacker, attacker),
                (float) config.shatter_damage);
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                config.shatter_slowness_ticks, config.shatter_slowness_amplifier));
        if (victim.level() instanceof ServerLevel level) {
            // The ice closes around them for as long as they are rooted, then bursts.
            CustomWeapons.effects().play(level, "frost_shatter", victim);
            CustomWeapons.effects().later(config.shatter_slowness_ticks + 1, () -> {
                if (!victim.isRemoved()) {
                    level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                                    ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.ICE.defaultBlockState()),
                            victim.getX(), victim.getY() + 1.0, victim.getZ(), 80, 0.5, 0.9, 0.5, 0.2);
                    level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                            SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 0.9f);
                }
            });
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 0.7f);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.SNOWFLAKE,
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(),
                    40, 0.4, 0.5, 0.4, 0.15);
            level.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(),
                    20, 0.3, 0.4, 0.3, 0.1);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY shatter player={} victim={} bonus={}",
                    attacker.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", config.shatter_damage));
        }
        attacker.displayClientMessage(Component.literal(
                        String.format("Shatter  +%.1f", config.shatter_damage))
                .withStyle(ChatFormatting.AQUA), true);
    }

    // ---- ultimate: Absolute Zero -----------------------------------------------------------------
    public static final String ABSOLUTE_ZERO = "absolute_zero";

    @Override
    public void onUltimate(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        if (!Ultimate.ready(player, ABSOLUTE_ZERO, "Absolute Zero")) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Ultimate.plant(player, 12);
        int hit = 0;
        java.util.List<LivingEntity> frozen = new java.util.ArrayList<>();
        for (LivingEntity victim : Ultimate.targets(player, config.absolute_zero_radius)) {
            if (Ultimate.strike(player, victim, config.absolute_zero_damage)) {
                hit++;
            }
            CustomWeapons.stuns().stun(victim, config.absolute_zero_freeze_ticks, "Absolute Zero", true);
            victim.setTicksFrozen(Math.max(victim.getTicksFrozen(), 200));
            CustomWeapons.effects().play(level, "frost_hit", victim);
            frozen.add(victim);
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, player.getX(), player.getY() + 1, player.getZ(), 150, 3.5, 1, 3.5, 0.02);
        // The thaw is the second half: the ice shatters off everyone still standing, with
        // more true damage and a slow, weak stagger afterwards.
        if (config.absolute_zero_shatter_damage > 0 && !frozen.isEmpty()) {
            CustomWeapons.effects().later(config.absolute_zero_freeze_ticks, () -> {
                int shattered = 0;
                for (LivingEntity victim : frozen) {
                    if (!victim.isAlive()) {
                        continue;
                    }
                    if (Ultimate.strike(player, victim, config.absolute_zero_shatter_damage)) {
                        shattered++;
                    }
                    if (config.absolute_zero_after_ticks > 0) {
                        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, config.absolute_zero_after_ticks, 2));
                        victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, config.absolute_zero_after_ticks, 0));
                    }
                    CustomWeapons.effects().play(level, "frost_hit", victim);
                    level.sendParticles(ParticleTypes.ITEM_SNOWBALL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(), 30, 0.4, 0.5, 0.4, 0.1);
                }
                level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 0.6f);
                if (config.log_abilities) {
                    CustomWeapons.LOGGER.info("ABILITY absolute_zero_shatter player={} victims={}", player.getName().getString(), shattered);
                }
                if (shattered > 0) {
                    player.displayClientMessage(Component.literal("Shatter  " + shattered + " hit").withStyle(ChatFormatting.AQUA), true);
                }
            });
        }
        Ultimate.fired(player, this, ABSOLUTE_ZERO, config.absolute_zero_cooldown_ticks, "Absolute Zero", hit, SoundEvents.GLASS_BREAK, 0.5f, config);
    }

    @Override
    public String ultimateLore(WeaponsConfig config) {
        return String.format("Absolute Zero - everything within %.0f blocks frozen solid for %.0fs and %.1f true damage; the thaw shatters for %.1f more and leaves them slow and weak for %.0fs. %ds",
                config.absolute_zero_radius, config.absolute_zero_freeze_ticks / 20.0, config.absolute_zero_damage,
                config.absolute_zero_shatter_damage, config.absolute_zero_after_ticks / 20.0, config.absolute_zero_cooldown_ticks / 20);
    }
}
