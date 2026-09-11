package dev.customweapons;

import dev.customweapons.weapon.AegisHammer;
import dev.customweapons.weapon.Bloodletter;
import dev.customweapons.weapon.Dawnbreaker;
import dev.customweapons.weapon.Starfall;
import dev.customweapons.weapon.Voidreaper;
import dev.customweapons.weapon.Frostbrand;
import dev.customweapons.weapon.GaleEdge;
import dev.customweapons.weapon.Hellfire;
import dev.customweapons.weapon.Stormpiercer;
import dev.customweapons.weapon.Tidecaller;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * The weapon registry, plus the item stamping that keeps crafted weapons in sync with the
 * config.
 *
 * <p>A weapon is identified <strong>only</strong> by the {@code cw_weapon} key inside its
 * {@code custom_data} component. Never by display name or lore: any player with an anvil can
 * rename a netherite sword to "Bloodletter", and if the name were the check that would be a
 * working Bloodletter obtainable without the recipe. {@code custom_data} cannot be set in
 * survival.
 */
public final class Weapons {

    public static final String TAG_ID = "cw_weapon";
    /** Bumped on every config load, so a reload re-stamps items that are already crafted. */
    public static final String TAG_GENERATION = "cw_gen";
    /** Identifies this individual weapon. The claim in the world folder names the real one. */
    public static final String TAG_SERIAL = "cw_serial";

    public static final Bloodletter BLOODLETTER = new Bloodletter();
    public static final GaleEdge GALE_EDGE = new GaleEdge();
    public static final Stormpiercer STORMPIERCER = new Stormpiercer();
    public static final AegisHammer AEGIS_HAMMER = new AegisHammer();
    public static final Frostbrand FROSTBRAND = new Frostbrand();
    public static final Tidecaller TIDECALLER = new Tidecaller();
    public static final Hellfire HELLFIRE = new Hellfire();
    public static final Dawnbreaker DAWNBREAKER = new Dawnbreaker();
    public static final Voidreaper VOIDREAPER = new Voidreaper();
    public static final Starfall STARFALL = new Starfall();

    public static final List<CustomWeapon> ALL =
            List.of(BLOODLETTER, GALE_EDGE, STORMPIERCER, AEGIS_HAMMER, FROSTBRAND, TIDECALLER, HELLFIRE,
                    DAWNBREAKER, VOIDREAPER, STARFALL);

    private static final int SWEEP_SLOTS_PER_PLAYER = 41;

    private Weapons() {
    }

    public static CustomWeapon byId(String id) {
        for (CustomWeapon weapon : ALL) {
            if (weapon.id().equals(id)) {
                return weapon;
            }
        }
        return null;
    }

    /** The weapon this stack is, or null. Disabled weapons behave as plain items. */
    public static CustomWeapon of(ItemStack stack, WeaponsConfig config) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String id = data.copyTag().getStringOr(TAG_ID, "");
        if (id.isEmpty()) {
            return null;
        }
        CustomWeapon weapon = byId(id);
        if (weapon == null || !weapon.enabled(config)) {
            return null;
        }
        return weapon.baseItem() == stack.getItem() ? weapon : null;
    }

    /** Builds a finished weapon from scratch, for the altar and {@code /customweapon give}. */
    public static ItemStack create(CustomWeapon weapon, WeaponsConfig config, int generation,
                                   String serial) {
        ItemStack stack = new ItemStack(weapon.baseItem());
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_ID, weapon.id());
        if (serial != null) {
            tag.putString(TAG_SERIAL, serial);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stamp(stack, weapon, config, generation);
        return stack;
    }

    /** This individual weapon's serial, or "" for one that has just come off a recipe. */
    public static String serialOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getStringOr(TAG_SERIAL, "");
    }

    private static void setSerial(ItemStack stack, String serial) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        tag.putString(TAG_SERIAL, serial);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * Applies the weapon's name, lore, attributes and glint.
     *
     * <p>Called on freshly crafted items and again after a config reload. The generation
     * counter in {@code custom_data} is what stops this running every sweep for every item.
     */
    public static void stamp(ItemStack stack, CustomWeapon weapon, WeaponsConfig config, int generation) {
        stack.set(DataComponents.CUSTOM_NAME, weapon.displayName());
        stack.set(DataComponents.LORE, new ItemLore(weapon.lore(config)));

        ItemAttributeModifiers attributes = weapon.attributes(config);
        if (attributes != null) {
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, attributes);
        }

        // With no resource pack, the glint is the only thing that makes a custom weapon look
        // different from the vanilla item it is built on.
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        // The optional resource pack (pack/) selects a 3D model by this string. A client
        // without the pack ignores it and sees the plain base item as before.
        float frame = Animations.frameOf(stack);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                frame > 0 ? List.of(frame) : List.of(), List.of(), List.of("cw:" + weapon.id()), List.of()));

        weapon.customise(stack, config);

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        tag.putString(TAG_ID, weapon.id());
        tag.putInt(TAG_GENERATION, generation);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * Re-stamps any weapon in a player's inventory that is not at the current generation.
     *
     * <p>Sweeping inventories rather than hooking the crafting result covers every way a
     * weapon can arrive: crafted, traded, pulled out of a chest, picked up off the floor, or
     * carried over from before a config change.
     */
    public static void sweep(MinecraftServer server, WeaponsConfig config, int generation,
                             Claims claims) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Inventory inventory = player.getInventory();
            int size = Math.min(inventory.getContainerSize(), SWEEP_SLOTS_PER_PLAYER);
            java.util.List<Integer> carried = new java.util.ArrayList<>();
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                CustomWeapon weapon = of(stack, config);
                if (weapon == null) {
                    continue;
                }
                if (config.unique_weapons && !resolveClaim(server, player, inventory, slot,
                        stack, weapon, config, claims)) {
                    continue;   // it was a copy; the slot no longer holds a weapon
                }
                weapon.maintain(stack, config);
                CustomData data = stack.get(DataComponents.CUSTOM_DATA);
                int stamped = data == null ? -1 : data.copyTag().getIntOr(TAG_GENERATION, -1);
                if (stamped != generation) {
                    stamp(stack, weapon, config, generation);
                    inventory.setChanged();
                }
                // A frame is only legitimate on the main-hand item while its animation runs.
                boolean animating = stack == player.getMainHandItem()
                        && CustomWeapons.animations().isPlaying(player.getUUID(), weapon.id());
                if (!animating && Animations.clearFrame(stack)) {
                    inventory.setChanged();
                }
                carried.add(slot);
            }
            if (config.one_weapon_per_player) {
                limitToOne(player, inventory, carried, config);
            }
        }
    }

    /**
     * One legendary per player. The one they were already carrying stays; any other is
     * dropped at their feet. Nothing is destroyed and nothing is refunded: the item is on
     * the floor for whoever wants it, which on an SMP is the point of the rule.
     */
    private static void limitToOne(ServerPlayer player, Inventory inventory,
                                   java.util.List<Integer> carried, WeaponsConfig config) {
        PlayerState state = CustomWeapons.state();
        if (carried.isEmpty()) {
            state.setCarrying(player, null);
            return;
        }
        String known = state.carrying(player);
        int keep = carried.get(0);
        for (int slot : carried) {
            CustomWeapon weapon = of(inventory.getItem(slot), config);
            if (weapon != null && weapon.id().equals(known)) {
                keep = slot;
                break;
            }
        }
        CustomWeapon kept = of(inventory.getItem(keep), config);
        state.setCarrying(player, kept == null ? null : kept.id());
        for (int slot : carried) {
            if (slot == keep) {
                continue;
            }
            ItemStack extra = inventory.getItem(slot);
            CustomWeapon weapon = of(extra, config);
            inventory.setItem(slot, ItemStack.EMPTY);
            inventory.setChanged();
            net.minecraft.world.entity.item.ItemEntity dropped = player.drop(extra, true);
            if (dropped != null) {
                dropped.setPickUpDelay(100);
            }
            player.sendSystemMessage(Component.literal("You can carry only one legendary at a time. ")
                    .withStyle(net.minecraft.ChatFormatting.RED)
                    .append(weapon == null ? Component.literal("It") : weapon.displayName())
                    .append(Component.literal(" is on the ground; drop ")
                            .withStyle(net.minecraft.ChatFormatting.RED))
                    .append(kept == null ? Component.literal("the other") : kept.displayName())
                    .append(Component.literal(" first if you want to swap.")
                            .withStyle(net.minecraft.ChatFormatting.RED)));
            CustomWeapons.LOGGER.info("LIMIT {} dropped {} (carrying {})",
                    player.getName().getString(), weapon == null ? "?" : weapon.id(),
                    kept == null ? "?" : kept.id());
        }
    }

    /**
     * Decides whether this stack is <em>the</em> weapon.
     *
     * <p>A weapon straight off the recipe has no serial. If nobody holds the claim it becomes
     * the real one here, which is the moment the recipe "goes off". If somebody already does,
     * this is a copy: it turns back into the plain base item and the rest of the price is
     * handed back, because taking a player's wither skulls for an item they cannot keep would
     * be robbery rather than a rule.
     *
     * @return true if the slot still holds a real weapon afterwards
     */
    private static boolean resolveClaim(MinecraftServer server, ServerPlayer player,
                                        Inventory inventory, int slot, ItemStack stack,
                                        CustomWeapon weapon, WeaponsConfig config, Claims claims) {
        String serial = serialOf(stack);
        Claims.Claim claim = claims.claimOf(weapon.id());

        if (claim == null) {
            String assigned = serial.isEmpty() ? Claims.newSerial() : serial;
            setSerial(stack, assigned);
            claims.claim(weapon.id(), assigned, player);
            if (config.announce_forging) {
                claims.announce(server, weapon, player);
            }
            CustomWeapons.LOGGER.info("CLAIM weapon={} serial={} owner={}",
                    weapon.id(), assigned, player.getName().getString());
            return true;
        }
        if (serial.equals(claim.serial())) {
            return true;
        }

        inventory.setItem(slot, new ItemStack(weapon.baseItem()));
        for (ItemCost cost : weapon.ingredients()) {
            if (cost.item() == weapon.baseItem()) {
                continue;   // they keep that: it is the plain item now sitting in the slot
            }
            ItemStack refund = new ItemStack(cost.item(), cost.count());
            if (!inventory.add(refund)) {
                player.drop(refund, false);
            }
        }
        inventory.setChanged();
        player.sendSystemMessage(Component.literal("")
                .append(weapon.displayName())
                .append(Component.literal(" has already been forged by " + claim.owner()
                        + ". There is only one on this world; your materials have been returned.")
                        .withStyle(net.minecraft.ChatFormatting.RED)));
        CustomWeapons.LOGGER.info("DUPLICATE weapon={} refunded to {}",
                weapon.id(), player.getName().getString());
        return false;
    }

    /** Grey, non-italic lore. Custom-named items default to italic, which reads as wrong. */
    public static Component loreLine(String text) {
        return Component.literal(text)
                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY).withItalic(false));
    }
}
