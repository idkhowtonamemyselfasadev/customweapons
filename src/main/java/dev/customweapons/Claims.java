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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which weapons have been made, and by whom.
 *
 * <p>On an SMP each of these is meant to be a landmark, not a kit: a fixed few of each on
 * the whole server ({@code weapon_copies}, three by default), and everyone knows who has
 * them. A claim records the weapon's serial, so a further one made later is recognisable as
 * a copy no matter how it was made.
 *
 * <p>The serial lives in the item's {@code custom_data}, and the claims live in the world
 * folder as one flat list, oldest first. An item whose serial is not on the list is not the
 * weapon.
 */
public final class Claims {

    public record Claim(String weapon, String serial, String owner, String ownerId, String forgedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Insertion-ordered, so the file and the {@code /customweapon claims} list read oldest first. */
    private final Map<String, List<Claim>> byWeapon = new LinkedHashMap<>();
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
                    byWeapon.computeIfAbsent(claim.weapon(), k -> new ArrayList<>()).add(claim);
                }
            }
            CustomWeapons.LOGGER.info("Loaded {} weapon claim(s)", all().size());
        } catch (Exception e) {
            // Starting fresh here would silently re-open every weapon for another forging,
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
            GSON.toJson(all(), writer);
            dirty = false;
        } catch (Exception e) {
            CustomWeapons.LOGGER.error("Could not write {}: {}", file, e.toString());
        }
    }

    /** How many of this weapon exist. */
    public int count(String weaponId) {
        List<Claim> claims = byWeapon.get(weaponId);
        return claims == null ? 0 : claims.size();
    }

    /** True once the world holds its full number of this weapon. A limit of 0 or less is no limit. */
    public boolean isFull(String weaponId, int limit) {
        return limit > 0 && count(weaponId) >= limit;
    }

    /** The claims for one weapon, oldest first; empty if none. */
    public List<Claim> claimsOf(String weaponId) {
        List<Claim> claims = byWeapon.get(weaponId);
        return claims == null ? List.of() : List.copyOf(claims);
    }

    /** The claim carrying this serial, or null: an item whose serial is not here is a copy. */
    public Claim bySerial(String weaponId, String serial) {
        for (Claim claim : claimsOf(weaponId)) {
            if (claim.serial().equals(serial)) {
                return claim;
            }
        }
        return null;
    }

    /** "Alice, Bob and Carol" - who holds this weapon, for messages. */
    public String owners(String weaponId) {
        List<Claim> claims = claimsOf(weaponId);
        if (claims.isEmpty()) {
            return "nobody";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < claims.size(); i++) {
            if (i > 0) {
                out.append(i == claims.size() - 1 ? " and " : ", ");
            }
            out.append(claims.get(i).owner());
        }
        return out.toString();
    }

    /** Every claim on the world, oldest first. */
    public List<Claim> all() {
        List<Claim> out = new ArrayList<>();
        for (List<Claim> claims : byWeapon.values()) {
            out.addAll(claims);
        }
        return out;
    }

    public static String newSerial() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public void claim(String weaponId, String serial, ServerPlayer owner) {
        byWeapon.computeIfAbsent(weaponId, k -> new ArrayList<>()).add(new Claim(weaponId, serial,
                owner == null ? "the server" : owner.getName().getString(),
                owner == null ? "" : owner.getUUID().toString(),
                Instant.now().toString()));
        dirty = true;
        save();
    }

    /** Frees one of a weapon to be made again - the most recent - for a legendary lost to a lava pit. */
    public Claim release(String weaponId) {
        List<Claim> claims = byWeapon.get(weaponId);
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        Claim removed = claims.remove(claims.size() - 1);
        if (claims.isEmpty()) {
            byWeapon.remove(weaponId);
        }
        dirty = true;
        save();
        return removed;
    }

    /**
     * The whole server hears about a legendary being made. That is the point of only a few.
     *
     * @param limit the world's {@code weapon_copies}, so the message can say which one this was
     */
    public void announce(MinecraftServer server, CustomWeapon weapon, ServerPlayer owner, int limit) {
        int made = count(weapon.id());
        String which = limit == 1
                ? " - the only one on this world."
                : limit > 1
                        ? " - " + made + " of " + limit + " on this world."
                        : ".";
        Component message = Component.literal("")
                .append(Component.literal(owner.getName().getString())
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" has forged ").withStyle(ChatFormatting.GRAY))
                .append(weapon.displayName())
                .append(Component.literal(which).withStyle(ChatFormatting.GRAY));
        server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
