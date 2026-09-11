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

import java.util.List;

/**
 * Bloodletter - a netherite sword that hits for 2.0 and bleeds its target to death.
 *
 * <p>Fast and feeble on purpose: the swing exists to stack bleed, not to hurt anyone. Against
 * a target it cannot keep hitting it is worse than punching.
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
                Weapons.loreLine("Bleed: every hit stacks, up to " + config.bleed_max_stacks),
                Weapons.loreLine(String.format("%.1f damage per stack every %.1fs for %.1fs",
                        config.bleed_damage_per_stack,
                        config.bleed_tick_interval_ticks / 20.0,
                        config.bleed_duration_ticks / 20.0)),
                Weapons.loreLine(String.format("Bleed does at most %.1f before it ends",
                        config.bleed_damage_budget)));
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

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        CustomWeapons.bleed().apply(attacker, victim, config);
        if (victim.level() instanceof net.minecraft.server.level.ServerLevel level) {
            CustomWeapons.effects().play(level, "blood_slash", victim);
        }
        CustomWeapons.animations().play(attacker, this, config);
    }
}
