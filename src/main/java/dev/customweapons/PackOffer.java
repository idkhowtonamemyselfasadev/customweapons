package dev.customweapons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Offers the 3D model resource pack in chat when a player joins.
 *
 * <p>Vanilla's own {@code resource-pack} server property either forces the pack on
 * everyone or nags on every join. This asks once, in chat, with a clickable answer: the
 * pack is sent only to players who click Install, and a player who clicks Never is not
 * asked again on this world. Both answers are ordinary commands, so a player can also just
 * type them.
 */
public final class PackOffer {

    /** Fixed id, so re-sending the pack replaces the previous copy instead of stacking. */
    private static final UUID PACK_ID = UUID.fromString("6f1c2a8e-3d4b-4c5e-9a0f-1b2c3d4e5f60");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Set<UUID> declined = new HashSet<>();
    private Path file;

    public void load(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("customweapons-pack-declined.json");
        declined.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            Set<String> ids = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Set<String>>() { }.getType());
            if (ids != null) {
                ids.forEach(id -> declined.add(UUID.fromString(id)));
            }
        } catch (Exception e) {
            CustomWeapons.LOGGER.error("Could not read {}: {}", file, e.toString());
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Set<String> ids = new HashSet<>();
            declined.forEach(id -> ids.add(id.toString()));
            Files.writeString(file, GSON.toJson(ids), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CustomWeapons.LOGGER.error("Could not write {}: {}", file, e.toString());
        }
    }

    public boolean configured(WeaponsConfig config) {
        return config.pack_offer_on_join && config.pack_url != null && !config.pack_url.isBlank();
    }

    /** The chat question, a moment after the join so it lands under the join message. */
    public void onJoin(ServerPlayer player, WeaponsConfig config) {
        if (!configured(config) || declined.contains(player.getUUID())) {
            return;
        }
        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal(config.pack_offer_message)
                .withStyle(ChatFormatting.GOLD));
        Component install = button("[Install]", ChatFormatting.GREEN, "/weaponpack install",
                "Download the 3D weapon models now (about 45 KB)");
        Component later = button("[Not now]", ChatFormatting.GRAY, "/weaponpack later",
                "Ask again next time you join");
        Component never = button("[Never]", ChatFormatting.RED, "/weaponpack never",
                "Do not ask again. /weaponpack install still works.");
        player.sendSystemMessage(Component.literal("   ")
                .append(install).append(Component.literal("   "))
                .append(later).append(Component.literal("   "))
                .append(never));
    }

    private static Component button(String label, ChatFormatting colour, String command, String hover) {
        return Component.literal(label).withStyle(style -> style
                .withColor(colour).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    /** Sends the pack. Not required: declining the client-side dialog just leaves it off. */
    public void install(ServerPlayer player, WeaponsConfig config) {
        if (config.pack_url == null || config.pack_url.isBlank()) {
            player.sendSystemMessage(Component.literal("No resource pack is configured on this server.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        declined.remove(player.getUUID());
        player.connection.send(new ClientboundResourcePackPushPacket(PACK_ID, config.pack_url,
                config.pack_sha1 == null ? "" : config.pack_sha1, false,
                Optional.of(Component.literal(config.pack_offer_message))));
        player.sendSystemMessage(Component.literal(
                        "Sending the 3D weapon models. If nothing happens, set this server's "
                                + "resource pack setting to Prompt or Enabled in the multiplayer menu.")
                .withStyle(ChatFormatting.GRAY));
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("PACK sent to {}", player.getName().getString());
        }
    }

    public void never(ServerPlayer player) {
        declined.add(player.getUUID());
        save();
        player.sendSystemMessage(Component.literal(
                        "Alright, no more asking. /weaponpack install brings the models back any time.")
                .withStyle(ChatFormatting.GRAY));
    }

    public void later(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Alright, next time then.")
                .withStyle(ChatFormatting.GRAY));
    }
}
