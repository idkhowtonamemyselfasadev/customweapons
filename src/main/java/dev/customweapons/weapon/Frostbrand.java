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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine(String.format("Frost: every hit chills and slows for %.0fs",
                        config.frost_slowness_ticks / 20.0)),
                Weapons.loreLine("Three quick hits freeze the target solid"),
                Weapons.loreLine(String.format("Shatter: a frozen target takes +%.1f and is rooted",
                        config.shatter_damage)),
                Weapons.loreLine("The frost thaws if you stop swinging"));
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
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        CustomWeapons.animations().play(attacker, this, config);
        int required = victim.getTicksRequiredToFreeze();
        int frozen = victim.getTicksFrozen() + config.frost_ticks_per_hit;
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                config.frost_slowness_ticks, config.frost_slowness_amplifier));

        if (frozen < required) {
            victim.setTicksFrozen(frozen);
            if (victim.level() instanceof ServerLevel level) {
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
        Hurt.deal(victim, victim.damageSources().indirectMagic(attacker, attacker),
                (float) config.shatter_damage);
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
                config.shatter_slowness_ticks, config.shatter_slowness_amplifier));
        if (victim.level() instanceof ServerLevel level) {
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
}
