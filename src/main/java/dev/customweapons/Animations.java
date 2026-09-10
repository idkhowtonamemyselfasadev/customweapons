package dev.customweapons;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attack animations for a vanilla client.
 *
 * <p>A client cannot be told to animate an item. It can be told which model to draw, and
 * it picks that from the item's components. So for a short while after an ability fires,
 * the held weapon carries a frame number in {@code custom_model_data}, counted up once a
 * tick; the resource pack has one pose of the weapon per number and the client swaps
 * between them, which is an animation at twenty frames a second. Everyone nearby sees it
 * in third person too, because the held item is synced to them like any other.
 *
 * <p>Changing components on the held item does not replay the equip bob: the client
 * replaces the visible item in place when it is still the same item type.
 *
 * <p>Without the pack, nothing happens: the number is ignored and the item looks as before.
 */
public final class Animations {

    private static final class Playing {
        final String weaponId;
        final int frames;
        int frame;

        Playing(String weaponId, int frames) {
            this.weaponId = weaponId;
            this.frames = frames;
            this.frame = 1;
        }
    }

    private final Map<UUID, Playing> playing = new HashMap<>();

    /** Starts (or restarts) the weapon's animation on the player's main hand. */
    public void play(ServerPlayer player, CustomWeapon weapon, WeaponsConfig config) {
        int frames = config.animation_ticks;
        if (frames <= 0) {
            return;
        }
        Playing anim = new Playing(weapon.id(), frames);
        if (setFrame(player, anim.weaponId, anim.frame)) {
            playing.put(player.getUUID(), anim);
        }
    }

    public void onTick(MinecraftServer server) {
        if (playing.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Playing>> it = playing.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Playing> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Playing anim = entry.getValue();
            if (player == null) {
                it.remove();
                continue;
            }
            anim.frame++;
            if (anim.frame > anim.frames) {
                setFrame(player, anim.weaponId, 0);
                it.remove();
                continue;
            }
            if (!setFrame(player, anim.weaponId, anim.frame)) {
                it.remove();
            }
        }
    }

    /**
     * Writes the frame onto the main-hand item, keeping the model selector string.
     *
     * @return false if the hand no longer holds that weapon, which ends the animation
     */
    private static boolean setFrame(ServerPlayer player, String weaponId, int frame) {
        ItemStack stack = player.getMainHandItem();
        CustomWeapon weapon = Weapons.of(stack, CustomWeapons.config());
        if (weapon == null || !weapon.id().equals(weaponId)) {
            return false;
        }
        CustomModelData current = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        List<String> strings = current == null || current.strings().isEmpty()
                ? List.of("cw:" + weaponId) : current.strings();
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                frame > 0 ? List.of((float) frame) : List.of(), List.of(), strings, List.of()));
        player.getInventory().setChanged();
        return true;
    }

    /** Whether this player's animation is running on this weapon right now. */
    public boolean isPlaying(UUID player, String weaponId) {
        Playing anim = playing.get(player);
        return anim != null && anim.weaponId.equals(weaponId);
    }

    /**
     * Removes a leftover frame. If the weapon leaves the main hand mid-animation - a slot
     * switch, a knock from a lightning strike, a death - the tick loop cannot reach it and
     * the frame would stay on the item for good, drawn as a sword stuck mid-swing. The
     * inventory sweep calls this for any weapon that is not animating.
     */
    public static boolean clearFrame(ItemStack stack) {
        CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (data == null || data.floats().isEmpty()) {
            return false;
        }
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                List.of(), data.flags(), data.strings(), data.colors()));
        return true;
    }

    /** Whether a stack is mid-animation, so a re-stamp keeps its frame. */
    public static float frameOf(ItemStack stack) {
        CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        return data == null || data.floats().isEmpty() ? 0f : data.floats().get(0);
    }

    public void forget(UUID player) {
        playing.remove(player);
    }

    public void clear() {
        playing.clear();
    }
}
