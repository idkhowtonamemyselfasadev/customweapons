package dev.customweapons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which weapons have been made, and by whom.
 *
 * <p>On an SMP each of these is meant to be a landmark, not a kit: one Bloodletter on the
 * whole server, forged once, and everyone knows who has it. A claim records the weapon's
 * serial, so a second one made later is recognisable as a copy no matter how it was made.
 *
 * <p>The serial lives in the item's {@code custom_data}, and the claim lives in the world
 * folder. An item whose serial does not match the claim is not the weapon.
 */
public final class Claims {

    public record Claim(String weapon, String serial, String owner, String ownerId, String forgedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Claim> byWeapon = new HashMap<>();
    private Path file;
    private boolean dirty;

    public void load(MinecraftServer server) {
        byWeapon.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("customweapons-claims.json");
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<Claim> loaded = GSON.fromJson(reader, new TypeToken<List<Claim>>() {
            }.getType());
            if (loaded != null) {
                for (Claim claim : loaded) {
                    byWeapon.put(claim.weapon(), claim);
                }
            }
            CustomWeapons.LOGGER.info("Loaded {} weapon claim(s)", byWeapon.size());
        } catch (Exception e) {
            // Starting fresh here would silently re-open every weapon for a second forging,
            // which on an SMP is the kind of thing people quit over. Say it loudly.
            CustomWeapons.LOGGER.error("Could not read {}; weapon uniqueness is NOT enforced "
                    + "this session: {}", file, e.toString());
        }
    }

    public void save() {
        if (!dirty || file == null) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(new ArrayList<>(byWeapon.values()), writer);
            dirty = false;
        } catch (Exception e) {
            CustomWeapons.LOGGER.error("Could not write {}: {}", file, e.toString());
        }
    }

    public boolean isClaimed(String weaponId) {
        return byWeapon.containsKey(weaponId);
    }

    public Claim claimOf(String weaponId) {
        return byWeapon.get(weaponId);
    }

    public List<Claim> all() {
        return new ArrayList<>(byWeapon.values());
    }

    public static String newSerial() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public void claim(String weaponId, String serial, ServerPlayer owner) {
        byWeapon.put(weaponId, new Claim(weaponId, serial,
                owner == null ? "the server" : owner.getName().getString(),
                owner == null ? "" : owner.getUUID().toString(),
                Instant.now().toString()));
        dirty = true;
        save();
    }

    /** Frees a weapon to be made again - for a legendary lost to a lava pit. */
    public Claim release(String weaponId) {
        Claim removed = byWeapon.remove(weaponId);
        if (removed != null) {
            dirty = true;
            save();
        }
        return removed;
    }

    /** The whole server hears about a legendary being made. That is the point of one. */
    public void announce(MinecraftServer server, CustomWeapon weapon, ServerPlayer owner) {
        Component message = Component.literal("")
                .append(Component.literal(owner.getName().getString())
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" has forged ").withStyle(ChatFormatting.GRAY))
                .append(weapon.displayName())
                .append(Component.literal(" - the only one on this world.")
                        .withStyle(ChatFormatting.GRAY));
        server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
