package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.ShockManager;
import dev.customweapons.Weapons;
import dev.customweapons.WeaponsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import dev.customweapons.ItemCost;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Stormpiercer - a bow whose fully drawn shots shock, chain and mark the target.
 *
 * <p>It cannot be enchanted at all: the {@code enchantable} component is stripped, so the
 * trade is Power V, Flame and Infinity for the shock and the Glowing.
 */
public final class Stormpiercer extends CustomWeapon {

    @Override
    public String id() {
        return "stormpiercer";
    }

    @Override
    public Item baseItem() {
        return Items.BOW;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.stormpiercer_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Stormpiercer")
                .withStyle(style -> style.withColor(ChatFormatting.BLUE)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        String executes = config.shock_instakill == null ? "" : String.join(", ",
                config.shock_instakill.stream()
                        .map(id -> id.substring(id.indexOf(':') + 1).replace('_', ' ') + "s")
                        .toList());
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Weapons.loreLine(String.format("Shock: a fully drawn hit deals +%.1f%s",
                config.shock_bonus_damage, config.shock_lightning ? " and calls lightning" : "")));
        if (!executes.isEmpty()) {
            lines.add(Weapons.loreLine("Kills " + executes + " outright"));
        }
        lines.add(Weapons.loreLine(String.format("Marks with Glowing for %.0fs",
                config.shock_glowing_ticks / 20.0)));
        lines.add(Weapons.loreLine(String.format("Chains to one target within %.0f for %.1f",
                config.shock_chain_range, config.shock_chain_damage)));
        lines.add(Weapons.loreLine(String.format("Cooldown %.0fs - fires normally meanwhile",
                config.shock_cooldown_ticks / 20.0)));
        lines.add(Weapons.loreLine("Cannot be enchanted"));
        return lines;
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.BOW, 1),
                new ItemCost(Items.AMETHYST_BLOCK, 3),
                new ItemCost(Items.LIGHTNING_ROD, 4),
                new ItemCost(Items.HEART_OF_THE_SEA, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.COPPER_BLOCK;
    }

    @Override
    public Block altarAccent() {
        return Blocks.AMETHYST_BLOCK;
    }

    @Override
    public void customise(ItemStack stack, WeaponsConfig config) {
        // Stops an enchanting table offering it anything at all.
        stack.remove(DataComponents.ENCHANTABLE);
    }

    @Override
    public void maintain(ItemStack stack, WeaponsConfig config) {
        // Removing `enchantable` is not enough on its own: that component governs the
        // enchanting table, while an anvil with a book, or /enchant, goes through the
        // enchantment's own supported_items tag and applies regardless. Tested, and Power I
        // went straight on. So any enchantment that lands on a Stormpiercer is stripped on
        // the next sweep, which makes the drawback true however the enchantment arrived.
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null && !enchantments.isEmpty()) {
            stack.remove(DataComponents.ENCHANTMENTS);
        }
    }

    @Override
    public void onArrowFired(ServerPlayer shooter, AbstractArrow arrow, ServerLevel level,
                             WeaponsConfig config) {
        // Draw strength read off the launch speed: a bow fires at charge * 3.0 blocks a tick.
        if (arrow.getDeltaMovement().length() < config.shock_min_arrow_speed) {
            return;
        }
        if (!CustomWeapons.cooldowns().ready(shooter, ShockManager.KEY)) {
            return;
        }
        CustomWeapons.shock().arm(arrow, config);
    }
}
