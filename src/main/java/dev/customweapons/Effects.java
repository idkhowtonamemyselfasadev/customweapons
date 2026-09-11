package dev.customweapons;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.math.Transformation;
import dev.customweapons.mixin.BlockDisplayInvoker;
import dev.customweapons.mixin.DisplayInvoker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * World effects built from block-display entities, played from {@code effects.json}.
 *
 * <p>A block display is a vanilla entity that draws one block at any position, size and
 * angle, and the client smooths between the transformations it is sent. So an ice cube
 * closing around a frozen player, or debris arcing out of a ground slam, is a handful of
 * these moved once a tick along keyframes. Every player sees them; no pack is involved.
 *
 * <p>The keyframes are authored in {@code pack/effects.py}, which also feeds the preview,
 * so what the browser shows is what the server plays.
 */
public final class Effects {

    private static final String TAG = "customweapons_fx";
    private static final Gson GSON = new Gson();

    /** One keyframe: centre, size and Euler rotation at tick t. */
    private record Key(float t, float[] c, float[] s, float[] r) {
    }

    private record PartSpec(String block, boolean follow, boolean bright, String ease, Integer glow, List<Key> keys) {
    }

    private record EffectSpec(int ticks, List<PartSpec> parts) {
    }

    private static final class Part {
        final PartSpec spec;
        final Display.BlockDisplay entity;

        Part(PartSpec spec, Display.BlockDisplay entity) {
            this.spec = spec;
            this.entity = entity;
        }
    }

    private static final class Playing {
        final EffectSpec spec;
        final List<Part> parts;
        final Vec3 origin;
        final Entity follow;
        int tick;

        Playing(EffectSpec spec, List<Part> parts, Vec3 origin, Entity follow) {
            this.spec = spec;
            this.parts = parts;
            this.origin = origin;
            this.follow = follow;
        }
    }

    private Map<String, EffectSpec> specs = Map.of();
    private final List<Playing> playing = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Later> later = new ArrayList<>();

    private record Later(int[] ticks, Runnable action) {
    }

    /** Runs something a few ticks from now, for the particle burst at the end of an effect. */
    public void later(int ticks, Runnable action) {
        later.add(new Later(new int[] {ticks}, action));
    }

    /** A rope of small blocks kept strung between two entities. */
    private static final class Line {
        final Entity from;
        final Entity to;
        final List<Display.BlockDisplay> beads;
        final float size;
        final int ticks;
        int tick;

        Line(Entity from, Entity to, List<Display.BlockDisplay> beads, float size, int ticks) {
            this.from = from;
            this.to = to;
            this.beads = beads;
            this.size = size;
            this.ticks = ticks;
        }
    }

    /** Strings {@code count} glowing beads between two entities for {@code ticks} ticks. */
    public void line(ServerLevel level, Entity from, Entity to, String blockId, int count, float size, int ticks) {
        if (!CustomWeapons.config().world_effects) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId)).orElse(Blocks.SEA_LANTERN);
        List<Display.BlockDisplay> beads = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
            if (display == null) {
                continue;
            }
            ((BlockDisplayInvoker) display).customweapons$setBlockState(block.defaultBlockState());
            DisplayInvoker d = (DisplayInvoker) display;
            d.customweapons$setTransformationInterpolationDuration(1);
            d.customweapons$setPosRotInterpolationDuration(1);
            d.customweapons$setViewRange(1.5f);
            d.customweapons$setShadowRadius(0f);
            d.customweapons$setBrightnessOverride(new Brightness(15, 15));
            display.setNoGravity(true);
            display.addTag(TAG);
            beads.add(display);
        }
        Line line = new Line(from, to, beads, size, ticks);
        string(line, true);
        lines.add(line);
        beads.forEach(level::addFreshEntity);
    }

    /** A ray of small blocks from one point to another, thinning out over {@code ticks}. */
    public void beam(ServerLevel level, Vec3 from, Vec3 to, String blockId, int count, float size, int ticks) {
        if (!CustomWeapons.config().world_effects) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(blockId)).orElse(Blocks.PACKED_ICE);
        List<PartSpec> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float u = (i + 0.5f) / count;
            Vec3 p = from.lerp(to, u);
            float[] c = {(float) (p.x - from.x), (float) (p.y - from.y), (float) (p.z - from.z)};
            float spin = i * 37f;
            List<Key> keys = List.of(
                    new Key(0, c, new float[] {0.01f, 0.01f, 0.01f}, new float[] {45, spin, 45}),
                    new Key(1 + i * (float) ticks / (2f * count), c, new float[] {size, size, size}, new float[] {45, spin + 90, 45}),
                    new Key(ticks, new float[] {c[0], c[1] - 0.3f, c[2]}, new float[] {0.01f, 0.01f, 0.01f}, new float[] {45, spin + 270, 45}));
            parts.add(new PartSpec(blockId, false, true, "linear", null, keys));
        }
        EffectSpec spec = new EffectSpec(ticks, parts);
        List<Part> made = new ArrayList<>(count);
        for (PartSpec p : parts) {
            Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
            if (display == null) {
                continue;
            }
            ((BlockDisplayInvoker) display).customweapons$setBlockState(block.defaultBlockState());
            DisplayInvoker d = (DisplayInvoker) display;
            d.customweapons$setTransformationInterpolationDuration(1);
            d.customweapons$setPosRotInterpolationDuration(1);
            d.customweapons$setViewRange(1.5f);
            d.customweapons$setShadowRadius(0f);
            d.customweapons$setBrightnessOverride(new Brightness(15, 15));
            display.setNoGravity(true);
            display.addTag(TAG);
            made.add(new Part(p, display));
        }
        Playing instance = new Playing(spec, made, from, null);
        apply(instance, 0, true);
        playing.add(instance);
        made.forEach(part -> level.addFreshEntity(part.entity));
    }

    private static void string(Line line, boolean initial) {
        Vec3 a = line.from.position().add(0, line.from.getBbHeight() * 0.6, 0);
        Vec3 b = line.to.position().add(0, line.to.getBbHeight() * 0.5, 0);
        int n = line.beads.size();
        for (int i = 0; i < n; i++) {
            double u = (i + 0.5) / n;
            Vec3 p = a.lerp(b, u);
            float s = line.size * (0.6f + 0.4f * (float) Math.sin(u * Math.PI));
            float spin = (line.tick * 25f + i * 40f) % 360f;
            Display.BlockDisplay bead = line.beads.get(i);
            if (initial) {
                bead.setPos(p.x, p.y, p.z);
            } else {
                bead.teleportTo(p.x, p.y, p.z);
            }
            Quaternionf rot = new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(45), (float) Math.toRadians(spin), (float) Math.toRadians(45));
            Vector3f offset = new Vector3f(-s / 2f, -s / 2f, -s / 2f).rotate(rot);
            DisplayInvoker d = (DisplayInvoker) bead;
            d.customweapons$setTransformation(new Transformation(offset, rot, new Vector3f(s, s, s), new Quaternionf()));
            d.customweapons$setTransformationInterpolationDelay(0);
        }
    }

    public void load() {
        try (var in = Effects.class.getResourceAsStream("/assets/customweapons/effects.json")) {
            if (in == null) {
                CustomWeapons.LOGGER.warn("effects.json missing from the jar; world effects are off");
                return;
            }
            specs = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, EffectSpec>>() { }.getType());
            CustomWeapons.LOGGER.info("Loaded {} world effects", specs.size());
        } catch (Exception e) {
            CustomWeapons.LOGGER.error("Could not read effects.json: {}", e.toString());
        }
    }

    /** Plays an effect at a point, or following an entity's feet when one is given. */
    public void play(ServerLevel level, String name, Vec3 origin, Entity follow) {
        if (!CustomWeapons.config().world_effects) {
            return;
        }
        EffectSpec spec = specs.get(name);
        if (spec == null) {
            return;
        }
        List<Part> parts = new ArrayList<>(spec.parts().size());
        for (PartSpec p : spec.parts()) {
            Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
            if (display == null) {
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(p.block())).orElse(Blocks.STONE);
            BlockState state = block.defaultBlockState();
            ((BlockDisplayInvoker) display).customweapons$setBlockState(state);
            DisplayInvoker d = (DisplayInvoker) display;
            d.customweapons$setTransformationInterpolationDuration(1);
            d.customweapons$setPosRotInterpolationDuration(1);
            d.customweapons$setViewRange(1.5f);
            d.customweapons$setShadowRadius(0f);
            if (p.bright()) {
                d.customweapons$setBrightnessOverride(new Brightness(15, 15));
            }
            if (p.glow() != null) {
                d.customweapons$setGlowColorOverride(p.glow());
                display.setGlowingTag(true);
            }
            display.setNoGravity(true);
            display.addTag(TAG);
            parts.add(new Part(p, display));
        }
        Playing instance = new Playing(spec, parts, origin, follow);
        apply(instance, 0, true);
        playing.add(instance);   // before spawning: the load hook discards untracked pieces
        for (Part part : parts) {
            level.addFreshEntity(part.entity);
        }
    }

    public void play(ServerLevel level, String name, Entity follow) {
        play(level, name, follow.position(), follow);
    }

    public void onTick(MinecraftServer server) {
        if (!later.isEmpty()) {
            Iterator<Later> lt = later.iterator();
            while (lt.hasNext()) {
                Later entry = lt.next();
                if (--entry.ticks()[0] <= 0) {
                    lt.remove();
                    entry.action().run();
                }
            }
        }
        if (!lines.isEmpty()) {
            Iterator<Line> lt = lines.iterator();
            while (lt.hasNext()) {
                Line line = lt.next();
                line.tick++;
                if (line.tick > line.ticks || line.from.isRemoved() || line.to.isRemoved()) {
                    line.beads.forEach(Entity::discard);
                    lt.remove();
                    continue;
                }
                string(line, false);
            }
        }
        if (playing.isEmpty()) {
            return;
        }
        Iterator<Playing> it = playing.iterator();
        while (it.hasNext()) {
            Playing instance = it.next();
            instance.tick++;
            if (instance.tick > instance.spec.ticks()
                    || (instance.follow != null && instance.follow.isRemoved())) {
                instance.parts.forEach(part -> part.entity.discard());
                it.remove();
                continue;
            }
            apply(instance, instance.tick, false);
        }
    }

    private static void apply(Playing instance, int tick, boolean initial) {
        Vec3 base = instance.follow != null ? instance.follow.position() : instance.origin;
        for (Part part : instance.parts) {
            PartSpec spec = part.spec;
            Key[] pair = around(spec.keys(), tick);
            if (pair == null) {
                // Before its first key: parked out of sight, at nothing.
                place(part, base, new float[] {0, -2, 0}, new float[] {0.001f, 0.001f, 0.001f},
                        new float[] {0, 0, 0}, initial);
                continue;
            }
            Key a = pair[0], b = pair[1];
            float u = a == b || b.t() == a.t() ? 0f : (tick - a.t()) / (b.t() - a.t());
            u = ease(spec.ease(), Math.max(0f, Math.min(1f, u)));
            float[] c = lerp(a.c(), b.c(), u);
            float[] s = lerp(a.s(), b.s(), u);
            float[] r = lerp(a.r(), b.r(), u);
            place(part, base, c, s, r, initial);
        }
    }

    /** The keys before and after this tick, or null before the first, or [last, last] after. */
    private static Key[] around(List<Key> keys, int tick) {
        if (keys.isEmpty() || tick < keys.get(0).t()) {
            return null;
        }
        for (int i = 0; i + 1 < keys.size(); i++) {
            if (tick >= keys.get(i).t() && tick <= keys.get(i + 1).t()) {
                return new Key[] {keys.get(i), keys.get(i + 1)};
            }
        }
        Key last = keys.get(keys.size() - 1);
        return new Key[] {last, last};
    }

    private static float ease(String kind, float u) {
        return switch (kind == null ? "smooth" : kind) {
            case "linear" -> u;
            case "out" -> 1 - (1 - u) * (1 - u);
            default -> u * u * (3 - 2 * u);
        };
    }

    private static float[] lerp(float[] a, float[] b, float u) {
        return new float[] {a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
    }

    /**
     * Positions one block so that it is centred on {@code base + c}, sized {@code s} and
     * turned by {@code r}. The entity sits at the centre; the block, which the client draws
     * from the entity's position out along +x +y +z, is pulled back by half its size before
     * the rotation, so it turns about its own middle.
     */
    private static void place(Part part, Vec3 base, float[] c, float[] s, float[] r, boolean initial) {
        Display.BlockDisplay entity = part.entity;
        double x = base.x + c[0], y = base.y + c[1], z = base.z + c[2];
        if (initial) {
            entity.setPos(x, y, z);
        } else {
            entity.teleportTo(x, y, z);
        }
        Quaternionf rot = new Quaternionf().rotationXYZ(
                (float) Math.toRadians(r[0]), (float) Math.toRadians(r[1]), (float) Math.toRadians(r[2]));
        Vector3f offset = new Vector3f(-s[0] / 2f, -s[1] / 2f, -s[2] / 2f).rotate(rot);
        DisplayInvoker d = (DisplayInvoker) entity;
        d.customweapons$setTransformation(new Transformation(offset, rot, new Vector3f(s[0], s[1], s[2]),
                new Quaternionf()));
        d.customweapons$setTransformationInterpolationDelay(0);
    }

    /**
     * An effect block that was saved with a chunk - the chunk unloaded mid-effect, or the
     * server crashed - comes back on chunk load with nobody driving it. Anything with the
     * tag that is not one of the pieces currently playing is a leftover and goes.
     */
    public void onEntityLoad(Entity entity) {
        if (!(entity instanceof Display) || !entity.getTags().contains(TAG)) {
            return;
        }
        for (Playing instance : playing) {
            for (Part part : instance.parts) {
                if (part.entity == entity) {
                    return;
                }
            }
        }
        for (Line line : lines) {
            if (line.beads.contains(entity)) {
                return;
            }
        }
        entity.discard();
    }

    /** Effects do not survive a restart: anything tagged from a crashed run is removed. */
    public void sweepStale(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Display && entity.getTags().contains(TAG)) {
                    entity.discard();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            CustomWeapons.LOGGER.info("Removed {} leftover effect entities", removed);
        }
    }

    public void clear() {
        for (Playing instance : playing) {
            instance.parts.forEach(part -> part.entity.discard());
        }
        playing.clear();
        for (Line line : lines) {
            line.beads.forEach(Entity::discard);
        }
        lines.clear();
        later.clear();
    }
}
