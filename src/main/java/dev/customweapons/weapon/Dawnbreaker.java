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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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

import java.util.List;

/**
 * Dawnbreaker - a golden sword that carries the sun.
 *
 * <p>Gold is the fastest-swinging tier and the most fragile; the sword is made unbreakable
 * so the legendary does not die on its second day. Every hit lights the target up, the
 * daylight makes it hit harder, a kill gives some of the life back, and the Sunstrike calls
 * a pillar of light down on whatever the wielder is looking at.
 */
public final class Dawnbreaker extends CustomWeapon {

    public static final String SUNSTRIKE = "sunstrike";

    @Override
    public String id() {
        return "dawnbreaker";
    }

    @Override
    public Item baseItem() {
        return Items.GOLDEN_SWORD;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.dawnbreaker_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Dawnbreaker")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine(String.format("Sunstrike: right-click to call a pillar of light down %.0f blocks ahead",
                        config.sunstrike_range)),
                Weapons.loreLine(String.format("%.1f to everything within %.0f blocks, double to the undead, cooldown %.0fs",
                        config.sunstrike_damage, config.sunstrike_radius, config.sunstrike_cooldown_ticks / 20.0)),
                Weapons.loreLine(String.format("Solar Brand: every hit burns for %.0fs, +%.1f while the sun is up",
                        config.solar_fire_ticks / 20.0, config.solar_daylight_bonus)),
                Weapons.loreLine(String.format("Daylight: a kill heals you %.1f", config.daylight_heal)),
                Weapons.loreLine("Never breaks"));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.GOLDEN_SWORD, 1),
                new ItemCost(Items.OCHRE_FROGLIGHT, 4),
                new ItemCost(Items.BLAZE_ROD, 3),
                new ItemCost(Items.TOTEM_OF_UNDYING, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.OCHRE_FROGLIGHT;
    }

    @Override
    public Block altarAccent() {
        return Blocks.GOLD_BLOCK;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.dawnbreaker_attack_damage, config.dawnbreaker_attack_speed);
    }

    @Override
    public void customise(ItemStack stack, WeaponsConfig config) {
        stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
    }

    @Override
    public void maintain(ItemStack stack, WeaponsConfig config) {
        if (!stack.has(DataComponents.UNBREAKABLE)) {
            stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
        }
    }

    @Override
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        int remaining = CustomWeapons.cooldowns().remaining(player, SUNSTRIKE);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(String.format("Sunstrike  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        Targeting.Ray ray = Targeting.look(player, level, config.sunstrike_range);
        if (ray.target() == null && !ray.wall()) {
            player.displayClientMessage(Component.literal("Sunstrike  nothing in reach")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        Vec3 at = ray.ground();
        CustomWeapons.cooldowns().set(player, SUNSTRIKE, config.sunstrike_cooldown_ticks, weapon);
        CustomWeapons.animations().play(player, this, config);

        // The mark first: a second of warning that the sky is about to fall on this spot.
        CustomWeapons.effects().play(level, "sun_telegraph", at, null);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.6f);
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            level.sendParticles(ParticleTypes.END_ROD, at.x + Math.cos(a) * config.sunstrike_radius,
                    at.y + 0.2, at.z + Math.sin(a) * config.sunstrike_radius, 1, 0, 0.05, 0, 0.0);
        }
        player.displayClientMessage(Component.literal("Sunstrike").withStyle(ChatFormatting.GOLD), true);

        CustomWeapons.effects().later(config.sunstrike_delay_ticks, () -> strike(level, player, at, config));
        return InteractionResult.SUCCESS;
    }

    private void strike(ServerLevel level, ServerPlayer player, Vec3 at, WeaponsConfig config) {
        CustomWeapons.effects().play(level, "sunstrike", at, null);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.8f, 1.8f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 1.2f);
        for (double y = 0; y < 12; y += 0.5) {
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + y, at.z, 2, 0.15, 0.1, 0.15, 0.01);
        }
        level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.5, at.z, 40, 1.2, 0.5, 1.2, 0.05);

        int hits = 0;
        for (LivingEntity victim : Targeting.around(level, player, at, config.sunstrike_radius)) {
            float amount = (float) config.sunstrike_damage;
            if (victim.isInvertedHealAndHarm()) {
                amount *= 2;
            }
            Hurt.deal(victim, victim.damageSources().indirectMagic(player, player), amount);
            victim.igniteForSeconds(config.sunstrike_fire_ticks / 20.0f);
            hits++;
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY sunstrike player={} at={} hits={}",
                    player.getName().getString(),
                    String.format("%.1f,%.1f,%.1f", at.x, at.y, at.z), hits);
        }
        player.displayClientMessage(Component.literal("Sunstrike  " + hits + " hit")
                .withStyle(ChatFormatting.GOLD), true);
    }

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        CustomWeapons.animations().play(attacker, this, config);
        victim.igniteForSeconds(config.solar_fire_ticks / 20.0f);
        if (attacker.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.FLAME, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5,
                    victim.getZ(), 10, 0.3, 0.4, 0.3, 0.03);
            if (level.isBrightOutside() && config.solar_daylight_bonus > 0) {
                Hurt.deal(victim, victim.damageSources().indirectMagic(attacker, attacker),
                        (float) config.solar_daylight_bonus);
                level.sendParticles(ParticleTypes.END_ROD, victim.getX(), victim.getY() + victim.getBbHeight() * 0.7,
                        victim.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
                if (config.log_abilities) {
                    CustomWeapons.LOGGER.info("ABILITY solar player={} victim={} bonus={}",
                            attacker.getName().getString(), victim.getName().getString(),
                            String.format("%.1f", config.solar_daylight_bonus));
                }
                attacker.displayClientMessage(Component.literal(
                                String.format("Solar Brand  +%.1f", config.solar_daylight_bonus))
                        .withStyle(ChatFormatting.GOLD), true);
            }
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.5f, 1.4f);
        }
    }

    @Override
    public void onKill(ServerPlayer killer, LivingEntity victim, WeaponsConfig config) {
        daylight(killer, victim, config);
    }

    private void daylight(ServerPlayer player, LivingEntity victim, WeaponsConfig config) {
        if (config.daylight_heal <= 0) {
            return;
        }
        player.heal((float) config.daylight_heal);
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1.0, player.getZ(),
                    12, 0.4, 0.5, 0.4, 0.0);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 1.8f);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY daylight player={} victim={} heal={}",
                    player.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", config.daylight_heal));
        }
        player.displayClientMessage(Component.literal(String.format("Daylight  +%.1f", config.daylight_heal))
                .withStyle(ChatFormatting.YELLOW), true);
    }
}
