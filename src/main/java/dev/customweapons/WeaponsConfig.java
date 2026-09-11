package dev.customweapons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every tunable number in the mod, read from {@code config/customweapons.json}.
 *
 * <p>Damage and speed are written as the <em>totals a player sees</em>, not as attribute
 * modifier values. A player's base attack damage is 1.0 and base attack speed is 4.0, so the
 * modifier actually written onto the item is {@code total - 1.0} and {@code total - 4.0}.
 * Doing that conversion here means nobody tuning the config has to know about it.
 *
 * <p>Stats are re-applied to items on a sweep, so editing this file and running
 * {@code /customweapon reload} retunes weapons that already exist in players' inventories.
 */
public final class WeaponsConfig {

    // ---------------------------------------------------------------- Bloodletter
    public boolean bloodletter_enabled = true;
    /** 4.0 a hit, fast: the swings stack bleed, and the bleed is most of the damage. */
    public double bloodletter_attack_damage = 4.0;
    /** Fast, so three hits stack full bleed in 1.5s. 4.0 x 2.0 = 8.0 DPS from swings. */
    public double bloodletter_attack_speed = 2.0;
    public int bleed_max_stacks = 3;
    public int bleed_duration_ticks = 60;
    public int bleed_tick_interval_ticks = 10;
    /** 1.5 per stack every 10 ticks = 3.0 DPS per stack, 9.0 DPS at three stacks. */
    public double bleed_damage_per_stack = 1.5;
    /**
     * Total damage one bleed instance may deal before it ends, refilled by a fresh hit.
     * Without this, 6.0 DPS on a window that every hit refreshes never stops.
     */
    public double bleed_damage_budget = 12.0;

    // ------------------------------------------------------------------ Gale Edge
    public boolean gale_edge_enabled = true;
    public double gale_edge_attack_damage = 7.0;
    public double gale_edge_attack_speed = 1.8;
    public int dash_cooldown_ticks = 160;
    public double dash_power = 1.5;
    public double dash_min_y = 0.3;
    public double dash_max_y = 0.8;
    public int dash_fall_immunity_ticks = 120;
    public int momentum_window_ticks = 40;
    public double momentum_bonus_damage = 4.0;

    // ---------------------------------------------------------------- Stormpiercer
    public boolean stormpiercer_enabled = true;
    /**
     * What a fully drawn arrow deals on its own, before armour. 10.0 is five hearts; a
     * vanilla bow lands 9 plus a random crit. Partial draws scale down with the draw, and
     * the Stormpiercer never crits, so the number is the number.
     */
    public double stormpiercer_full_damage = 10.0;
    public int shock_cooldown_ticks = 600;
    /**
     * A bow shoots at {@code charge * 3.0} blocks/tick, so 2.7 is a draw of 0.9. Reading the
     * charge off the arrow's speed avoids needing a mixin on the bow.
     */
    public double shock_min_arrow_speed = 2.7;
    /** On top of the arrow: 10 + 6 = 16, eight hearts, when the lightning is ready. */
    public double shock_bonus_damage = 6.0;
    public int shock_glowing_ticks = 120;
    public double shock_chain_range = 5.0;
    public double shock_chain_damage = 3.0;
    /** A lightning bolt strikes whatever a fully drawn arrow hits. */
    public boolean shock_lightning = true;
    /**
     * A real bolt rather than a visual one: it sets fires, charges creepers and turns
     * villagers into witches, exactly as a storm would. Off, the bolt is flash and thunder
     * only and the shock's own damage is all that lands.
     */
    public boolean shock_lightning_fire = false;
    /**
     * A shocked target is stunned: rooted where it stands, unable to move or jump, for
     * this many ticks. 40 is two seconds, the same as the cage. 0 turns it off.
     */
    public int shock_stun_ticks = 40;
    /** Entity types a fully drawn hit kills outright, whatever their health. */
    public java.util.List<String> shock_instakill =
            java.util.List.of("minecraft:creeper", "minecraft:skeleton");

    // --------------------------------------------------------------- Aegis Hammer
    public boolean aegis_hammer_enabled = true;
    public double aegis_hammer_attack_damage = 11.0;
    public double aegis_hammer_attack_speed = 0.9;
    public int slam_cooldown_ticks = 300;
    public double slam_radius = 5.0;
    public double slam_damage = 8.0;
    public int slam_slowness_ticks = 80;
    public int slam_slowness_amplifier = 1;
    public int slam_resistance_ticks = 100;
    public double slam_knock_up = 0.35;
    public double slam_knock_out = 0.4;

    // ----------------------------------------------------------------- Frostbrand
    public boolean frostbrand_enabled = true;
    /** Netherite-sword DPS on an iron sword: the frost is the reason, this is the excuse. */
    public double frostbrand_attack_damage = 8.0;
    public double frostbrand_attack_speed = 1.6;
    /**
     * Frost added per hit, in the same units as powder snow. Vanilla thaws 2 ticks a tick,
     * so at 1.4 swings a second three hits in a row reach the 140 that counts as frozen
     * solid and the third one shatters.
     */
    public int frost_ticks_per_hit = 70;
    public int frost_slowness_ticks = 40;
    public int frost_slowness_amplifier = 1;
    public double shatter_damage = 8.0;
    /**
     * The ice beam, on right-click: a ray up to this far that freezes the first thing it
     * touches solid - the ice closes around it, it cannot move, and it takes the damage.
     */
    public double frost_beam_range = 24.0;
    public double frost_beam_damage = 6.0;
    public int frost_beam_freeze_ticks = 60;
    public int frost_beam_cooldown_ticks = 160;
    /** How long a shattered target stays frozen solid (rooted), and the pause before the frost can build again. */
    public int shatter_slowness_ticks = 60;
    public int shatter_slowness_amplifier = 3;
    public int shatter_cooldown_ticks = 100;

    // ----------------------------------------------------------------- Tidecaller
    public boolean tidecaller_enabled = true;
    /** A vanilla trident is 9.0. */
    public double tidecaller_attack_damage = 10.0;
    public double tidecaller_attack_speed = 1.1;
    /** Melee and thrown hits on a target in water or rain deal this much extra. */
    public double tide_wet_bonus_damage = 4.0;
    public int harpoon_cooldown_ticks = 200;
    /** How hard a harpooned target is yanked towards the thrower, blocks a tick. */
    public double harpoon_pull_power = 1.4;
    public double harpoon_pull_lift = 0.35;

    // ------------------------------------------------------------------- Hellfire
    public boolean hellfire_enabled = true;
    public int hellfire_cooldown_ticks = 160;
    /**
     * Blast strength. TNT is 4.0 and a creeper 3.0; at 2.0 a direct hit is about 15 before
     * armour and falls off fast with distance. Blocks are never broken.
     */
    public double hellfire_explosion_power = 2.0;
    /** Leave fire behind, like a ghast fireball would. */
    public boolean hellfire_fire = false;

    // ---------------------------------------------------------------------- altars
    /** Altars generate in newly generated overworld chunks only. */
    public boolean altars_enabled = true;
    /**
     * One temple per square of this many chunks, placed at a seed-derived offset inside it -
     * the same way vanilla spaces its structures, so two are never neighbours. 24 chunks is
     * roughly one every 400 blocks: they are large enough that finding one should be an
     * expedition, not a stroll.
     */
    public int altar_spacing_chunks = 24;

    // -------------------------------------------------------------------- servers
    /**
     * One of each weapon on the whole world, ever. A second one, however it was made, is
     * turned back into its parts and its materials handed back.
     */
    public boolean unique_weapons = true;
    /**
     * A player may carry only one legendary at a time. A second one, however it arrived,
     * is dropped at their feet with a note; the one they had first is the one they keep.
     */
    public boolean one_weapon_per_player = true;
    /** One temple per weapon too, so the seven of them are the map's landmarks. */
    public boolean one_altar_per_weapon = true;
    /** Tell everyone when a legendary is forged. */
    public boolean announce_forging = true;
    /** Nobody mines the altar out from under the server. */
    public boolean protect_altars = true;
    /** A dropped legendary neither burns nor despawns. */
    public boolean protect_dropped_weapons = true;

    // -------------------------------------------------------------- resource pack
    /**
     * Push the pack to every player on join with the vanilla "download or leave" dialog.
     * A player who clicks No, has server packs disabled, or whose download fails is
     * kicked with {@code pack_kick_message}. Off, the mod asks politely in chat instead.
     */
    public boolean pack_required = true;
    public String pack_kick_message = "This server needs its resource pack. Click Yes on the download prompt, or set Server Resource Packs to Enabled in the server's edit screen.";
    /** With pack_required off: ask each player in chat, on join, whether they want the models. */
    public boolean pack_offer_on_join = true;
    /** A direct download link to the pack zip. A GitHub release asset works. */
    public String pack_url = "https://github.com/idkhowtonamemyselfasadev/customweapons/releases/download/v1.2.1/CustomWeapons-Models.zip";
    /** SHA-1 of that zip. Change it with the file, or clients keep a stale cached copy. */
    public String pack_sha1 = "2dd32bea93bdc4e37be62da8fb4b34405f60a12c";
    public String pack_offer_message = "This server has 3D models for the legendary weapons. Want them?";

    // --------------------------------------------------------------- world effects
    /**
     * The 3D effects in the world: the ice closing around a frozen target, the slam's
     * debris, the lightning cage, the ember burst. Built from block-display entities, so
     * every player sees them, pack or not. Off, only the vanilla particles remain.
     */
    public boolean world_effects = true;

    // ------------------------------------------------------------------ animations
    /**
     * Ticks an attack animation runs for after an ability fires. The held weapon carries a
     * frame number in custom_model_data for that long and the resource pack shows one pose
     * per frame. 0 turns it off. Only players with the pack see anything.
     */
    public int animation_ticks = 10;

    // ----------------------------------------------------------------------- misc
    /** How often held/carried weapons are re-stamped with their stats, in ticks. */
    public int stat_sweep_interval_ticks = 5;
    /**
     * Logs one line per ability trigger. Off by default because it is noisy on a busy
     * server; the test harness turns it on so it can assert on what actually fired rather
     * than on what the health bars looked like afterwards.
     */
    public boolean log_abilities = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("customweapons.json");
    }

    public static WeaponsConfig load() {
        Path file = path();
        if (!Files.exists(file)) {
            WeaponsConfig fresh = new WeaponsConfig();
            fresh.save();
            return fresh;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            WeaponsConfig loaded = GSON.fromJson(reader, WeaponsConfig.class);
            if (loaded == null) {
                throw new IOException("config file is empty");
            }
            return loaded;
        } catch (Exception e) {
            // A broken config must not take the server down or silently wipe itself; run on
            // defaults and leave the file alone so the mistake can be fixed by hand.
            CustomWeapons.LOGGER.error("Could not read {}, using defaults: {}", file, e.toString());
            return new WeaponsConfig();
        }
    }

    public void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            CustomWeapons.LOGGER.error("Could not write {}: {}", file, e.toString());
        }
    }
}
