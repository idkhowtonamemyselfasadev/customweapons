package dev.customweapons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Weapon altars: small shrines that generate in the overworld.
 *
 * <p>Stand at one holding everything a weapon costs, right-click it, and the altar forges the
 * weapon and takes the materials. It is a second way to obtain the weapons for players who
 * never learn the recipe - the price is the same, so it changes discovery, not balance.
 *
 * <p>Placement is deterministic from the world seed, laid out one candidate per square region
 * of chunks the way vanilla spaces its structures, so two altars are never neighbours and the
 * same seed always produces the same map. The mod records the sites it has built in the world
 * folder; that record, not the blocks, is what makes a block an altar, so a player who
 * rebuilds the shape out of their own lodestone gets a lodestone.
 */
public final class Altars {

    /** One site: the lodestone's position and the weapon that altar forges. */
    public record Site(String weapon, int x, int y, int z) {
    }

    private static final int SALT = 0x41_4C_54_52;   // "ALTR"
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<Long, Site> sites = new HashMap<>();
    /** Chunks seen during a chunk load, handled on the next server tick instead. */
    private final List<ChunkPos> pending = Collections.synchronizedList(new ArrayList<>());
    private boolean dirty;
    private Path file;

    // ------------------------------------------------------------------- persistence

    public void load(MinecraftServer server) {
        sites.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("customweapons-altars.json");
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<Site> loaded = GSON.fromJson(reader, new TypeToken<List<Site>>() {
            }.getType());
            if (loaded != null) {
                for (Site site : loaded) {
                    sites.put(key(site.x(), site.y(), site.z()), site);
                }
            }
            CustomWeapons.LOGGER.info("Loaded {} weapon altars", sites.size());
        } catch (Exception e) {
            // Losing the file would make every existing altar an ordinary lodestone, so say so
            // loudly rather than quietly starting a fresh one.
            CustomWeapons.LOGGER.error("Could not read {}; existing altars will not respond: {}",
                    file, e.toString());
        }
    }

    public void save() {
        if (!dirty || file == null) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(new ArrayList<>(sites.values()), writer);
            dirty = false;
        } catch (Exception e) {
            CustomWeapons.LOGGER.error("Could not write {}: {}", file, e.toString());
        }
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    // --------------------------------------------------------------------- placement

    /** Called from chunk load, which is not necessarily the server thread. */
    public void onChunkLoad(ChunkPos pos) {
        pending.add(pos);
    }

    public void onTick(MinecraftServer server, WeaponsConfig config) {
        if (pending.isEmpty()) {
            return;
        }
        List<ChunkPos> batch;
        synchronized (pending) {
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        if (!config.altars_enabled) {
            return;
        }
        ServerLevel overworld = server.overworld();
        for (ChunkPos chunk : batch) {
            try {
                considerChunk(overworld, chunk, config);
                labelSitesIn(overworld, chunk);
            } catch (Exception e) {
                CustomWeapons.LOGGER.error("Altar placement failed at {}: {}", chunk, e.toString());
            }
        }
        save();
    }

    /**
     * Seeds altars into a world that already exists.
     *
     * <p>Altars are built when a region's one candidate chunk is loaded, and pre-generating
     * a world does not load anything for the mod to see. This walks every region within the
     * radius, loads its candidate chunk, and lets the usual placement run - so the result is
     * exactly the set of altars the world would have grown on its own. Regions are visited
     * in a seeded shuffle rather than in rings, so the one-per-weapon temples end up spread
     * across the map instead of clustered around the player.
     *
     * @return how many altars were built
     */
    public int seed(ServerLevel level, BlockPos around, int radiusBlocks, WeaponsConfig config) {
        int spacing = Math.max(2, config.altar_spacing_chunks);
        int regionRadius = Math.max(1, radiusBlocks / (spacing * 16) + 1);
        int centreX = Math.floorDiv(SectionPos.blockToSectionCoord(around.getX()), spacing);
        int centreZ = Math.floorDiv(SectionPos.blockToSectionCoord(around.getZ()), spacing);
        List<int[]> regions = new ArrayList<>();
        for (int rx = centreX - regionRadius; rx <= centreX + regionRadius; rx++) {
            for (int rz = centreZ - regionRadius; rz <= centreZ + regionRadius; rz++) {
                regions.add(new int[] {rx, rz});
            }
        }
        java.util.Collections.shuffle(regions, new Random(level.getSeed() ^ SALT));
        int before = sites.size();
        for (int[] region : regions) {
            Random random = new Random(level.getSeed()
                    ^ (region[0] * 341873128712L + region[1] * 132897987541L) ^ SALT);
            int cx = region[0] * spacing + random.nextInt(spacing);
            int cz = region[1] * spacing + random.nextInt(spacing);
            if (Math.hypot(cx * 16 - around.getX(), cz * 16 - around.getZ()) > radiusBlocks) {
                continue;
            }
            level.getChunk(cx, cz);   // loads it from disk, or generates it
            ChunkPos chunk = new ChunkPos(cx, cz);
            try {
                considerChunk(level, chunk, config);
                labelSitesIn(level, chunk);
            } catch (Exception e) {
                CustomWeapons.LOGGER.error("Altar seeding failed at {}: {}", chunk, e.toString());
            }
            if (config.one_altar_per_weapon && Weapons.ALL.stream()
                    .filter(w -> w.enabled(config)).allMatch(w -> hasAltarFor(w.id()))) {
                break;   // every weapon has its temple; nothing more would be built
            }
        }
        save();
        return sites.size() - before;
    }

    private void considerChunk(ServerLevel level, ChunkPos chunk, WeaponsConfig config) {
        int spacing = Math.max(2, config.altar_spacing_chunks);
        int regionX = Math.floorDiv(chunk.x, spacing);
        int regionZ = Math.floorDiv(chunk.z, spacing);
        Random random = new Random(level.getSeed()
                ^ (regionX * 341873128712L + regionZ * 132897987541L) ^ SALT);
        if (chunk.x != regionX * spacing + random.nextInt(spacing)
                || chunk.z != regionZ * spacing + random.nextInt(spacing)) {
            return;
        }
        // One temple per chunk, ever. The position is derived from the surface height, and a
        // temple raises that surface by its own foundation - so without this check, reloading
        // a chunk that already has one builds a second temple on the roof of the first.
        for (Site existing : sites.values()) {
            if (SectionPos.blockToSectionCoord(existing.x()) == chunk.x
                    && SectionPos.blockToSectionCoord(existing.z()) == chunk.z) {
                return;
            }
        }

        List<CustomWeapon> candidates = Weapons.ALL.stream()
                .filter(w -> w.enabled(config))
                // One temple per weapon: once a weapon's temple exists somewhere, no second
                // one is ever built, so a world ends up with exactly four landmarks and
                // finding them is the content.
                .filter(w -> !config.one_altar_per_weapon || !hasAltarFor(w.id()))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        CustomWeapon weapon = candidates.get(random.nextInt(candidates.size()));

        int x = chunk.getMinBlockX() + 8;
        int z = chunk.getMinBlockZ() + 8;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        BlockPos floor = new BlockPos(x, surface, z);
        BlockPos altar = floor.above(ALTAR_HEIGHT);

        if (sites.containsKey(key(altar.getX(), altar.getY(), altar.getZ()))) {
            return;   // already built here; a player may since have taken it apart
        }
        // Nothing sensible to stand on: open water, lava, or the top of the world.
        BlockState below = level.getBlockState(floor.below());
        if (!below.getFluidState().isEmpty() || below.isAir()
                || surface <= level.getMinY() + 2 || surface >= level.getMaxY() - 6) {
            return;
        }
        build(level, floor, weapon);
        Site site = new Site(weapon.id(), altar.getX(), altar.getY(), altar.getZ());
        sites.put(key(site.x(), site.y(), site.z()), site);
        dirty = true;
        ensureLabels(level, site);
        CustomWeapons.LOGGER.info("Weapon altar placed: {} at {} {} {}",
                weapon.id(), site.x(), site.y(), site.z());
    }

    /** How far above the platform floor the lodestone sits. */
    public static final int ALTAR_HEIGHT = 3;
    /** Half-width of the temple floor: 8 gives a 17x17 footprint. */
    private static final int OUTER = 8;
    /** Half-width of the colonnade the roof stands on. */
    private static final int COLONNADE = 7;
    /** Half-width of the raised sanctum in the middle. */
    private static final int SANCTUM = 2;
    private static final int PILLAR_TOP = 7;
    private static final int CLEAR_HEIGHT = 16;

    /**
     * A temple, not a plinth.
     *
     * <p>17x17 tiled floor on a foundation that reaches down to solid ground, a colonnade of
     * eight pillars in the weapon's colours carrying an architrave, low walls with the
     * weapon's accent block set into them, soul braziers at the inner corners, lanterns on
     * chains between the pillars, and a stepped roof left open in the middle so the altar's
     * label is readable from outside and light falls on the pedestal.
     *
     * <p>Blocks go in with {@code UPDATE_CLIENTS} only. Firing neighbour updates for six
     * thousand blocks would be pointlessly expensive, and would knock the chains and lanterns
     * off before the block they hang from exists.
     *
     * @param floor the block layer the temple floor occupies; the lodestone ends up three above
     */
    public void build(ServerLevel level, BlockPos floor, CustomWeapon weapon) {
        clear(level, floor);
        foundation(level, floor);
        tiledFloor(level, floor);
        colonnade(level, floor, weapon);
        walls(level, floor, weapon);
        roof(level, floor, weapon);
        sanctum(level, floor, weapon);
    }

    private void set(ServerLevel level, BlockPos pos, BlockState state) {
        if (pos.getY() <= level.getMinY() || pos.getY() >= level.getMaxY()) {
            return;
        }
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private void set(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        set(level, pos, block.defaultBlockState());
    }

    /** Everything the temple will occupy, plus a block of headroom, becomes air. */
    private void clear(ServerLevel level, BlockPos floor) {
        for (int dx = -OUTER - 1; dx <= OUTER + 1; dx++) {
            for (int dz = -OUTER - 1; dz <= OUTER + 1; dz++) {
                for (int dy = 1; dy <= CLEAR_HEIGHT; dy++) {
                    set(level, floor.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
    }

    /**
     * Fills only what is not already solid, down six blocks.
     *
     * <p>On flat ground that is a single course under the floor; on a slope it becomes a
     * plinth, so the temple never hangs in the air with its foundations showing.
     */
    private void foundation(ServerLevel level, BlockPos floor) {
        for (int dx = -OUTER; dx <= OUTER; dx++) {
            for (int dz = -OUTER; dz <= OUTER; dz++) {
                for (int dy = -1; dy >= -6; dy--) {
                    BlockPos pos = floor.offset(dx, dy, dz);
                    BlockState existing = level.getBlockState(pos);
                    if (dy == -1 || existing.isAir() || !existing.getFluidState().isEmpty()) {
                        set(level, pos, Blocks.DEEPSLATE_BRICKS);
                    }
                }
            }
        }
    }

    /** Concentric bands, so the floor reads as a building rather than a slab. */
    private void tiledFloor(ServerLevel level, BlockPos floor) {
        for (int dx = -OUTER; dx <= OUTER; dx++) {
            for (int dz = -OUTER; dz <= OUTER; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                net.minecraft.world.level.block.Block block;
                if (ring == OUTER) {
                    block = Blocks.DEEPSLATE_BRICKS;
                } else if (ring == COLONNADE) {
                    block = Blocks.DEEPSLATE_TILES;
                } else if ((dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz)) && ring > SANCTUM) {
                    block = Blocks.DEEPSLATE_TILES;   // spokes radiating from the sanctum
                } else {
                    block = Blocks.POLISHED_DEEPSLATE;
                }
                set(level, floor.offset(dx, 0, dz), block);
            }
        }
        // A step up at each entrance, so the doorways read as doorways.
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos step = floor.relative(facing, OUTER);
            set(level, step.above(), Blocks.DEEPSLATE_BRICK_SLAB);
        }
    }

    /** Eight pillars, their capitals, and the architrave they carry. */
    private void colonnade(ServerLevel level, BlockPos floor, CustomWeapon weapon) {
        int[][] pillars = {
                {-COLONNADE, -COLONNADE}, {COLONNADE, -COLONNADE},
                {-COLONNADE, COLONNADE}, {COLONNADE, COLONNADE},
                {0, -COLONNADE}, {0, COLONNADE}, {-COLONNADE, 0}, {COLONNADE, 0},
        };
        for (int[] pillar : pillars) {
            for (int dy = 1; dy <= PILLAR_TOP; dy++) {
                set(level, floor.offset(pillar[0], dy, pillar[1]), weapon.altarPillar());
            }
            set(level, floor.offset(pillar[0], PILLAR_TOP + 1, pillar[1]), Blocks.CHISELED_DEEPSLATE);
        }
        // Architrave: the ring the pillars carry, and a cornice one course above it.
        for (int dx = -COLONNADE; dx <= COLONNADE; dx++) {
            for (int dz = -COLONNADE; dz <= COLONNADE; dz++) {
                if (Math.abs(dx) != COLONNADE && Math.abs(dz) != COLONNADE) {
                    continue;
                }
                set(level, floor.offset(dx, PILLAR_TOP + 2, dz), Blocks.DEEPSLATE_BRICKS);
                set(level, floor.offset(dx, PILLAR_TOP + 3, dz), Blocks.DEEPSLATE_BRICK_SLAB);
            }
        }
        // Lanterns on chains, hung from the architrave between the pillars.
        int[][] lanterns = {
                {-4, -COLONNADE}, {4, -COLONNADE}, {-4, COLONNADE}, {4, COLONNADE},
                {-COLONNADE, -4}, {-COLONNADE, 4}, {COLONNADE, -4}, {COLONNADE, 4},
        };
        for (int[] spot : lanterns) {
            set(level, floor.offset(spot[0], PILLAR_TOP + 1, spot[1]), Blocks.DEEPSLATE_BRICKS);
            set(level, floor.offset(spot[0], PILLAR_TOP, spot[1]), Blocks.IRON_CHAIN);
            set(level, floor.offset(spot[0], PILLAR_TOP - 1, spot[1]), Blocks.IRON_CHAIN);
            set(level, floor.offset(spot[0], PILLAR_TOP - 2, spot[1]), Blocks.SOUL_LANTERN);
        }
    }

    /** Low walls between the pillars, with the weapon's colour set into them, and doorways. */
    private void walls(ServerLevel level, BlockPos floor, CustomWeapon weapon) {
        for (int dx = -COLONNADE; dx <= COLONNADE; dx++) {
            for (int dz = -COLONNADE; dz <= COLONNADE; dz++) {
                boolean edge = Math.abs(dx) == COLONNADE || Math.abs(dz) == COLONNADE;
                if (!edge) {
                    continue;
                }
                boolean pillar = Math.abs(dx) == COLONNADE && Math.abs(dz) == COLONNADE
                        || dx == 0 || dz == 0;
                if (pillar) {
                    continue;   // pillars and the four doorways stay open
                }
                set(level, floor.offset(dx, 1, dz), Blocks.DEEPSLATE_BRICKS);
                boolean window = (Math.abs(dx) + Math.abs(dz)) % 2 == 1;
                set(level, floor.offset(dx, 2, dz), window
                        ? weapon.altarAccent().defaultBlockState()
                        : Blocks.DEEPSLATE_TILES.defaultBlockState());
                set(level, floor.offset(dx, 3, dz), Blocks.DEEPSLATE_BRICK_SLAB);
            }
        }
    }

    /**
     * A stepped roof of rings, open in the middle.
     *
     * <p>Solid would hide the altar's label and put the pedestal in the dark; the oculus keeps
     * both, and the steps read as a ziggurat from outside.
     */
    private void roof(ServerLevel level, BlockPos floor, CustomWeapon weapon) {
        int y = PILLAR_TOP + 4;
        for (int half = COLONNADE - 1; half >= 2; half -= 2, y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.abs(dx) != half && Math.abs(dz) != half) {
                        continue;   // ring only: the middle stays open to the sky
                    }
                    boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                    set(level, floor.offset(dx, y, dz), corner
                            ? weapon.altarPillar().defaultBlockState()
                            : Blocks.DEEPSLATE_TILES.defaultBlockState());
                }
            }
        }
    }

    /** The raised middle: braziers, the pedestal, and the lodestone itself. */
    private void sanctum(ServerLevel level, BlockPos floor, CustomWeapon weapon) {
        for (int dx = -SANCTUM; dx <= SANCTUM; dx++) {
            for (int dz = -SANCTUM; dz <= SANCTUM; dz++) {
                boolean edge = Math.abs(dx) == SANCTUM || Math.abs(dz) == SANCTUM;
                set(level, floor.offset(dx, 1, dz), edge
                        ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                        : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
        }
        for (int dx = -SANCTUM; dx <= SANCTUM; dx += SANCTUM * 2) {
            for (int dz = -SANCTUM; dz <= SANCTUM; dz += SANCTUM * 2) {
                set(level, floor.offset(dx, 2, dz), weapon.altarAccent());
            }
        }
        // Braziers, just outside the sanctum steps.
        int b = SANCTUM + 2;
        for (int dx = -b; dx <= b; dx += b * 2) {
            for (int dz = -b; dz <= b; dz += b * 2) {
                set(level, floor.offset(dx, 1, dz), Blocks.DEEPSLATE_BRICKS);
                set(level, floor.offset(dx, 2, dz), Blocks.SOUL_CAMPFIRE);
            }
        }
        set(level, floor.above(2), Blocks.CHISELED_DEEPSLATE);
        set(level, floor.above(ALTAR_HEIGHT), Blocks.LODESTONE);
    }

    // ------------------------------------------------------------------- the label

    /** Scoreboard tag on every label entity, so they can be found and replaced. */
    private static final String LABEL_TAG = "customweapons_altar_label";
    private static final double LINE_SPACING = 0.30;

    /**
     * Floats the weapon's name and its price above the altar.
     *
     * <p>One invisible armour stand per line, because a vanilla client renders a custom name tag
     * with no resource pack and no mod. A text display would be one entity instead of five,
     * but its text is only reachable through synced entity data with no public setter.
     */
    public void ensureLabels(ServerLevel level, Site site) {
        CustomWeapon weapon = Weapons.byId(site.weapon());
        if (weapon == null) {
            return;
        }
        BlockPos altar = new BlockPos(site.x(), site.y(), site.z());
        AABB box = new AABB(altar).inflate(3.0);
        List<ArmorStand> existing = level.getEntitiesOfClass(ArmorStand.class, box,
                stand -> stand.getTags().contains(LABEL_TAG));

        List<Component> lines = labelLines(weapon);
        if (existing.size() == lines.size()) {
            return;   // already labelled
        }
        for (ArmorStand stale : existing) {
            stale.discard();
        }
        double top = site.y() + 1.9;
        for (int i = 0; i < lines.size(); i++) {
            ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
            stand.snapTo(site.x() + 0.5, top - i * LINE_SPACING, site.z() + 0.5, 0.0f, 0.0f);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            // Not a marker: setMarker is private in 1.21.11 and reaching it would mean a
            // mixin for a cosmetic. Invulnerable and floating a metre above a three-block
            // pedestal, so nothing standing on the platform can reach it anyway.
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setNoBasePlate(true);
            stand.setCustomName(lines.get(i));
            stand.setCustomNameVisible(true);
            stand.addTag(LABEL_TAG);
            level.addFreshEntity(stand);
        }
    }

    /** The weapon's name, then one line per ingredient. */
    private List<Component> labelLines(CustomWeapon weapon) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("\u2726 Altar of the ").withStyle(ChatFormatting.GRAY)
                .append(weapon.displayName()));
        for (ItemCost cost : weapon.ingredients()) {
            lines.add(Component.literal(cost.count() + "x ")
                    .append(Component.translatable(cost.item().getDescriptionId()))
                    .withStyle(ChatFormatting.WHITE));
        }
        return lines;
    }

    /** Re-labels any altar in a chunk that has just come back. */
    private void labelSitesIn(ServerLevel level, ChunkPos chunk) {
        boolean any = false;
        for (Site site : sites.values()) {
            if (SectionPos.blockToSectionCoord(site.x()) == chunk.x
                    && SectionPos.blockToSectionCoord(site.z()) == chunk.z) {
                any = true;
            }
        }
        if (!any) {
            return;
        }
        // A chunk's blocks arrive before its entities. Counting the labels before the stored
        // ones are in would find none and add five more on top, every time the chunk came
        // back - so a chunk whose entities are not in yet is looked at again next tick.
        if (!level.areEntitiesLoaded(chunk.toLong())) {
            pending.add(chunk);
            return;
        }
        for (Site site : new ArrayList<>(sites.values())) {
            if (SectionPos.blockToSectionCoord(site.x()) == chunk.x
                    && SectionPos.blockToSectionCoord(site.z()) == chunk.z) {
                ensureLabels(level, site);
            }
        }
    }

    /** Builds an altar under a player's feet, for {@code /customweapon altar}. */
    public Site placeAt(ServerLevel level, BlockPos feet, CustomWeapon weapon) {
        BlockPos floor = feet.below();
        build(level, floor, weapon);
        BlockPos altar = floor.above(ALTAR_HEIGHT);
        Site site = new Site(weapon.id(), altar.getX(), altar.getY(), altar.getZ());
        sites.put(key(site.x(), site.y(), site.z()), site);
        dirty = true;
        ensureLabels(level, site);
        save();
        return site;
    }

    // ---------------------------------------------------------------------- forging

    /** The altar at this position, or one up to two blocks of pedestal below it. */
    public Site siteAt(BlockPos pos) {
        for (int up = 0; up <= 2; up++) {
            Site site = sites.get(key(pos.getX(), pos.getY() + up, pos.getZ()));
            if (site != null) {
                return site;
            }
        }
        return null;
    }

    /**
     * Forges the weapon if the player is carrying its price, and takes the materials.
     *
     * @return true if the click was consumed
     */
    public boolean use(ServerPlayer player, Site site, WeaponsConfig config, int generation) {
        CustomWeapon weapon = Weapons.byId(site.weapon());
        if (weapon == null || !weapon.enabled(config)) {
            player.displayClientMessage(Component.literal("The altar is dormant.")
                    .withStyle(ChatFormatting.GRAY), true);
            return true;
        }
        Claims claims = CustomWeapons.claims();
        if (config.unique_weapons && claims.isClaimed(weapon.id())) {
            // Spent, not broken. The temple stays as a monument to whoever got there first.
            Claims.Claim claim = claims.claimOf(weapon.id());
            player.displayClientMessage(Component.literal("The altar is spent. ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(weapon.displayName())
                    .append(Component.literal(" was forged by " + claim.owner())
                            .withStyle(ChatFormatting.GRAY)), false);
            if (player.level() instanceof ServerLevel level) {
                level.playSound(null, site.x(), site.y(), site.z(),
                        SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.7f, 0.5f);
            }
            return true;
        }
        Map<ItemCost, Integer> missing = missing(player, weapon);
        if (!missing.isEmpty()) {
            player.displayClientMessage(Component.literal("The altar forges ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(weapon.displayName()), false);
            for (Map.Entry<ItemCost, Integer> entry : missing.entrySet()) {
                player.displayClientMessage(Component.literal("  need " + entry.getValue() + "x ")
                        .append(Component.translatable(entry.getKey().item().getDescriptionId()))
                        .withStyle(ChatFormatting.RED), false);
            }
            player.level().playSound(null, site.x(), site.y(), site.z(),
                    SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.7f, 0.7f);
            return true;
        }

        take(player, weapon);
        String serial = Claims.newSerial();
        ItemStack forged = Weapons.create(weapon, config, generation, serial);
        if (!player.getInventory().add(forged)) {
            player.drop(forged, false);
        }
        if (config.unique_weapons) {
            claims.claim(weapon.id(), serial, player);
            if (config.announce_forging) {
                claims.announce(((ServerLevel) player.level()).getServer(), weapon, player);
            }
        }

        if (player.level() instanceof ServerLevel level) {
            level.playSound(null, site.x(), site.y(), site.z(),
                    SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 1.2f);
            level.playSound(null, site.x(), site.y(), site.z(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.6f, 1.4f);
            level.sendParticles(ParticleTypes.ENCHANT, site.x() + 0.5, site.y() + 1.2, site.z() + 0.5,
                    60, 0.6, 0.6, 0.6, 0.4);
            level.sendParticles(ParticleTypes.END_ROD, site.x() + 0.5, site.y() + 1.0, site.z() + 0.5,
                    25, 0.3, 0.4, 0.3, 0.05);
        }
        player.displayClientMessage(Component.literal("The altar forges ")
                .withStyle(ChatFormatting.GRAY).append(weapon.displayName()), true);
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ALTAR forge player={} weapon={} at {} {} {}",
                    player.getName().getString(), weapon.id(), site.x(), site.y(), site.z());
        }
        return true;
    }

    /** What the player is short of, in order, for the message. */
    private Map<ItemCost, Integer> missing(ServerPlayer player, CustomWeapon weapon) {
        Map<ItemCost, Integer> missing = new LinkedHashMap<>();
        for (ItemCost cost : weapon.ingredients()) {
            int have = 0;
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (payable(stack, cost)) {
                    have += stack.getCount();
                }
            }
            if (have < cost.count()) {
                missing.put(cost, cost.count() - have);
            }
        }
        return missing;
    }

    /**
     * A stack that may be spent on this cost.
     *
     * <p>Custom weapons are never spent. The Bloodletter costs a netherite sword, and quietly
     * eating the player's Gale Edge-tier gear because it happens to be the right base item
     * would be indefensible.
     */
    private boolean payable(ItemStack stack, ItemCost cost) {
        return !stack.isEmpty() && stack.is(cost.item())
                && Weapons.of(stack, CustomWeapons.config()) == null;
    }

    private void take(ServerPlayer player, CustomWeapon weapon) {
        Inventory inventory = player.getInventory();
        for (ItemCost cost : weapon.ingredients()) {
            int owed = cost.count();
            // Cheapest first: an unenchanted stack goes before an enchanted one, so the altar
            // does not swallow a Sharpness V sword while a plain one sits in the next slot.
            for (int pass = 0; pass < 2 && owed > 0; pass++) {
                for (int slot = 0; slot < inventory.getContainerSize() && owed > 0; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (!payable(stack, cost)) {
                        continue;
                    }
                    boolean enchanted = stack.isEnchanted();
                    if (pass == 0 && enchanted) {
                        continue;
                    }
                    int take = Math.min(owed, stack.getCount());
                    stack.shrink(take);
                    if (stack.isEmpty()) {
                        inventory.setItem(slot, ItemStack.EMPTY);
                    }
                    owed -= take;
                }
            }
        }
        inventory.setChanged();
    }

    public java.util.Collection<Site> all() {
        return sites.values();
    }

    public int count() {
        return sites.size();
    }

    public boolean hasAltarFor(String weaponId) {
        for (Site site : sites.values()) {
            if (site.weapon().equals(weaponId)) {
                return true;
            }
        }
        return false;
    }

    /** True if this block is an altar, or the pedestal holding one up. */
    public boolean isProtected(BlockPos pos) {
        for (int down = 0; down <= ALTAR_HEIGHT; down++) {
            if (sites.containsKey(key(pos.getX(), pos.getY() + down, pos.getZ()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds every known altar to the current design, in place.
     *
     * <p>An altar recorded before the temple existed is still a working altar sitting in the
     * middle of the old plinth. This puts the building back around it without moving the
     * lodestone, which is what the site record points at.
     */
    public int rebuildAll(ServerLevel level) {
        int rebuilt = 0;
        for (Site site : new ArrayList<>(sites.values())) {
            CustomWeapon weapon = Weapons.byId(site.weapon());
            if (weapon == null) {
                continue;
            }
            BlockPos altar = new BlockPos(site.x(), site.y(), site.z());
            level.getChunkAt(altar);   // a site out at the edge of the map may not be loaded
            build(level, altar.below(ALTAR_HEIGHT), weapon);
            ensureLabels(level, site);
            rebuilt++;
        }
        return rebuilt;
    }

    /** The nearest altar to a position, for {@code /customweapon altar find}. */
    public Site nearest(BlockPos pos) {
        Site best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Site site : sites.values()) {
            double distance = pos.distSqr(new BlockPos(site.x(), site.y(), site.z()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = site;
            }
        }
        return best;
    }

    /** A lodestone that is not a recorded altar is just a lodestone. */
    public boolean isLodestone(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.LODESTONE);
    }
}
