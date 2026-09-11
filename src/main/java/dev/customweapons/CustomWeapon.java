package dev.customweapons;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * One custom weapon: its identity, the components stamped onto it, and its behaviour.
 *
 * <p>Adding a fifth weapon is one new subclass plus one line in {@link Weapons#ALL} and one
 * recipe JSON. Nothing else in the mod needs to know about it.
 *
 * <p>The recipe only stamps {@code custom_data} with the weapon's id. Everything else -
 * name, lore, attributes, glint - is applied here on a sweep, so a config change retunes
 * weapons that are already in players' inventories instead of only newly crafted ones.
 */
public abstract class CustomWeapon {

    /** Matches the {@code cw_weapon} value in the recipe JSON. */
    public abstract String id();

    /** The vanilla item the weapon rides on. No new item is ever registered. */
    public abstract Item baseItem();

    public abstract Component displayName();

    public abstract List<Component> lore(WeaponsConfig config);

    public abstract boolean enabled(WeaponsConfig config);

    /** Null keeps the base item's vanilla attributes (used by the bow). */
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return null;
    }

    /** Anything else to stamp onto the item, e.g. removing enchantability. */
    public void customise(ItemStack stack, WeaponsConfig config) {
    }

    /**
     * Run on every sweep, not just when the item is first stamped.
     *
     * <p>For rules that have to hold against whatever the world does to the item afterwards -
     * an anvil, a command, a creative-mode edit - rather than only at the moment it is made.
     */
    public void maintain(ItemStack stack, WeaponsConfig config) {
    }

    /** Right-click with the weapon in hand. */
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        return InteractionResult.PASS;
    }

    /** A melee hit with the weapon that actually dealt damage. */
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
    }

    /**
     * What the altar consumes to forge this weapon.
     *
     * <p>This mirrors the recipe JSON by hand. The two have to be changed together - there is
     * no shared source, because the recipe has to be data the vanilla client can be told
     * about and this is code.
     */
    public abstract List<ItemCost> ingredients();

    /** The pillar block of this weapon's altar. */
    public abstract Block altarPillar();

    /** The accent block ringing this weapon's pedestal. */
    public abstract Block altarAccent();

    /** An arrow that has just been fired by a player holding this weapon. */
    public void onArrowFired(ServerPlayer shooter, AbstractArrow arrow, ServerLevel level,
                             WeaponsConfig config) {
    }

    /**
     * A projectile this weapon armed on firing has hit a living target.
     *
     * <p>Only fires for projectiles handed to {@link Projectiles#arm}; {@link #onArrowFired}
     * is where a weapon decides whether a given shot deserves its ability.
     */
    public void onProjectileHit(ServerPlayer shooter, LivingEntity victim, AbstractArrow projectile,
                                WeaponsConfig config) {
    }

    /** Once a server tick, for weapons that keep per-player state between events. */
    public void onTick(net.minecraft.server.MinecraftServer server, WeaponsConfig config) {
    }

    /**
     * Something died to this weapon's wielder: a swing, or ability damage credited to them.
     *
     * <p>From the death event, not the damage event: Fabric's after-damage hook does not run
     * for the blow that kills, so a heal-on-kill that lives there never fires.
     */
    public void onKill(ServerPlayer killer, LivingEntity victim, WeaponsConfig config) {
    }

    /** A player has left: drop anything kept for them. */
    public void forget(java.util.UUID player) {
    }

    /**
     * Every tick for an armed projectile still in the world.
     *
     * @return false to stop tracking it
     */
    public boolean onProjectileTick(ServerPlayer shooter, AbstractArrow projectile, ServerLevel level,
                                    WeaponsConfig config) {
        return true;
    }
}
