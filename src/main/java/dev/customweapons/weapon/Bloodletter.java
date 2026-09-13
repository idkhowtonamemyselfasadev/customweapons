package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.Stats;
import dev.customweapons.Weapons;
import dev.customweapons.WeaponsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.customweapons.ItemCost;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import dev.customweapons.Ultimate;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import java.util.List;

/**
 * Bloodletter - a fast netherite sword with a Rage on right-click, and a bleed that only
 * the ultimate (Crimson Nova) applies.
 *
 * <p>Since 1.6.3 a plain swing does not bleed: the bleed was the whole weapon and it made
 * every fight a matter of tagging someone once and walking away. Now the swings are the
 * weapon, Rage sharpens them for ten seconds, and the nova is where the blood is.
 */
public final class Bloodletter extends CustomWeapon {

    @Override
    public String id() {
        return "bloodletter";
    }

    @Override
    public Item baseItem() {
        return Items.NETHERITE_SWORD;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.bloodletter_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Bloodletter")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_RED)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine(String.format("Rage: right-click for Strength %s for %.0fs",
                        roman(config.rage_amplifier + 1), config.rage_duration_ticks / 20.0)),
                Weapons.loreLine(String.format("Cooldown %.0fs", config.rage_cooldown_ticks / 20.0)),
                Weapons.loreLine("Bleed: only the Crimson Nova (sneak + left-click) makes them bleed"),
                Weapons.loreLine(String.format("%.1f damage per stack every %.1fs, up to %d stacks, %.1f in all",
                        config.bleed_damage_per_stack, config.bleed_tick_interval_ticks / 20.0,
                        config.bleed_max_stacks, config.bleed_damage_budget)));
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> Integer.toString(n);
        };
    }

    public static final String RAGE = "rage";

    /** Rage: Strength for a while. No target needed, so a click always costs the cooldown. */
    @Override
    public net.minecraft.world.InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        int remaining = CustomWeapons.cooldowns().remaining(player, RAGE);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(String.format("Rage  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return net.minecraft.world.InteractionResult.PASS;
        }
        player.addEffect(new MobEffectInstance(MobEffects.STRENGTH, config.rage_duration_ticks, config.rage_amplifier, false, true, true));
        CustomWeapons.cooldowns().set(player, RAGE, config.rage_cooldown_ticks, weapon);
        CustomWeapons.animations().play(player, this, config);
        if (player.level() instanceof ServerLevel level) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.RAVAGER_ROAR, net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.4f);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, player.getX(), player.getY() + 1.4, player.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, player.getX(), player.getY() + 1, player.getZ(), 40, 0.6, 0.6, 0.6, 0.02);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY rage player={} strength={} ticks={}",
                    player.getName().getString(), config.rage_amplifier + 1, config.rage_duration_ticks);
        }
        player.displayClientMessage(Component.literal(String.format("Rage  Strength %s for %.0fs",
                roman(config.rage_amplifier + 1), config.rage_duration_ticks / 20.0)).withStyle(ChatFormatting.RED), true);
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.NETHERITE_SWORD, 1),
                new ItemCost(Items.WITHER_SKELETON_SKULL, 3),
                new ItemCost(Items.GHAST_TEAR, 4),
                new ItemCost(Items.NETHERITE_INGOT, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.RED_NETHER_BRICKS;
    }

    @Override
    public Block altarAccent() {
        return Blocks.REDSTONE_BLOCK;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.bloodletter_attack_damage, config.bloodletter_attack_speed);
    }

    /** A swing is just a swing now: the blood is visual, the bleed belongs to the nova. */
    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        if (victim.level() instanceof net.minecraft.server.level.ServerLevel level) {
            CustomWeapons.effects().play(level, "blood_slash", victim);
        }
        CustomWeapons.animations().play(attacker, this, config);
    }

    // ---- ultimate: Crimson Nova ----------------------------------------------------------------
    public static final String NOVA = "crimson_nova";

    @Override
    public void onUltimate(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        if (!Ultimate.ready(player, NOVA, "Crimson Nova")) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Ultimate.spin(player, 12);
        int hit = 0;
        for (LivingEntity victim : Ultimate.targets(player, config.nova_radius)) {
            // Full bleed stacks at once, then the true damage on top.
            for (int i = 0; i < config.bleed_max_stacks; i++) {
                CustomWeapons.bleed().apply(player, victim, config);
            }
            if (Ultimate.strike(player, victim, config.nova_damage)) {
                hit++;
                CustomWeapons.effects().play(level, "blood_burst", victim);
            }
        }
        if (hit > 0) {
            player.heal((float) (config.nova_heal_per_victim * hit));
        }
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, player.getX(), player.getY() + 1, player.getZ(), 200, 3, 1, 3, 0.05);
        Ultimate.fired(player, this, NOVA, config.nova_cooldown_ticks, "Crimson Nova", hit, SoundEvents.WITHER_HURT, 0.6f, config);
    }

    @Override
    public String ultimateLore(WeaponsConfig config) {
        return String.format("Crimson Nova - everything within %.0f blocks bleeds fully and takes %.1f true damage; heals you %.0f per victim. %ds", config.nova_radius, config.nova_damage, config.nova_heal_per_victim, config.nova_cooldown_ticks / 20);
    }
}
