package dev.customweapons;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * Custom Weapons - seven craftable weapons with abilities.
 *
 * <p>Server side only. Nothing here touches a client class, no new registry entry is added
 * and no custom recipe serializer exists, so a completely vanilla client connects, crafts
 * these weapons and sees every ability play out in vanilla sounds and particles.
 */
public final class CustomWeapons implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("CustomWeapons");

    private static WeaponsConfig config = new WeaponsConfig();
    private static final Cooldowns COOLDOWNS = new Cooldowns();
    private static final PlayerState STATE = new PlayerState(COOLDOWNS);
    private static final BleedManager BLEED = new BleedManager(COOLDOWNS);
    private static final Stuns STUNS = new Stuns(COOLDOWNS);
    private static final ShockManager SHOCK = new ShockManager(COOLDOWNS);
    private static final Projectiles PROJECTILES = new Projectiles(COOLDOWNS);
    private static final Altars ALTARS = new Altars();
    private static final Claims CLAIMS = new Claims();
    private static final PackOffer PACK = new PackOffer();
    private static final Animations ANIMATIONS = new Animations();
    private static final Effects EFFECTS = new Effects();

    /**
     * Bumped on every config load. Items carry the generation they were stamped at, so a
     * reload re-stamps weapons that already exist instead of only affecting new ones.
     */
    private static int generation;

    /** Level 2 - the same level vanilla requires for /give. */
    private static final Permission OP = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private int sweepTick;

    public static WeaponsConfig config() {
        return config;
    }

    public static Cooldowns cooldowns() {
        return COOLDOWNS;
    }

    public static PlayerState state() {
        return STATE;
    }

    public static BleedManager bleed() {
        return BLEED;
    }

    public static Stuns stuns() {
        return STUNS;
    }

    public static ShockManager shock() {
        return SHOCK;
    }

    public static Effects effects() {
        return EFFECTS;
    }

    public static Animations animations() {
        return ANIMATIONS;
    }

    public static Projectiles projectiles() {
        return PROJECTILES;
    }

    public static Altars altars() {
        return ALTARS;
    }

    public static Claims claims() {
        return CLAIMS;
    }

    @Override
    public void onInitializeServer() {
        reloadConfig();
        EFFECTS.load();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ALTARS.load(server);
            CLAIMS.load(server);
            PACK.load(server);
            EFFECTS.sweepStale(server);
            LOGGER.info("CustomWeapons ready: {} weapons craftable, {} altars known",
                    Weapons.ALL.stream().filter(w -> w.enabled(config)).count(), ALTARS.count());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EFFECTS.clear();
            ALTARS.save();
            CLAIMS.save();
        });
        // A legendary that burns in the lava it was dropped into, or despawns while its owner
        // is offline, is gone from the world for good - so on an SMP it does neither.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
            if (config.protect_altars && !level.isClientSide() && ALTARS.isProtected(pos)) {
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.displayClientMessage(Component.literal(
                            "The altar does not yield.").withStyle(ChatFormatting.GRAY), true);
                }
                return false;
            }
            return true;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (level.dimension() == Level.OVERWORLD) {
                ALTARS.onChunkLoad(chunk.getPos());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        UseItemCallback.EVENT.register(this::onUseItem);
        UseBlockCallback.EVENT.register(this::onUseBlock);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::allowDamage);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(this::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            BLEED.clear(entity.getUUID());
            if (source.getEntity() instanceof ServerPlayer killer && killer != entity) {
                CustomWeapon weapon = Weapons.of(killer.getMainHandItem(), config);
                if (weapon != null) {
                    weapon.onKill(killer, entity, config);
                }
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            EFFECTS.onEntityLoad(entity);
            if (entity instanceof AbstractArrow arrow) {
                onArrowFired(arrow, level);
            } else if (config.protect_dropped_weapons && entity instanceof ItemEntity item
                    && Weapons.of(item.getItem(), config) != null) {
                item.setUnlimitedLifetime();
                item.setInvulnerable(true);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PACK.onJoin(handler.getPlayer(), config));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            COOLDOWNS.forget(player.getUUID());
            ANIMATIONS.forget(player.getUUID());
            STATE.forget(player.getUUID());
            BLEED.clear(player.getUUID());
            for (CustomWeapon weapon : Weapons.ALL) {
                weapon.forget(player.getUUID());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                register(dispatcher));
    }

    private static void reloadConfig() {
        config = WeaponsConfig.load();
        // Seconds since the epoch, not a counter: a counter restarts at 1 with every server
        // start, so a weapon stamped "1" by last week's build is taken for current by this
        // week's and never picks up what the new build adds. This cost the Aegis Hammer its
        // 3D model. Time only moves forward, so every start re-stamps everything once.
        generation = Math.max(generation + 1, (int) (System.currentTimeMillis() / 1000L));
    }

    // --------------------------------------------------------------------- ticking

    private void onTick(MinecraftServer server) {
        COOLDOWNS.onTick(server);
        STATE.onTick(server);
        BLEED.onTick(server, config);
        SHOCK.onTick();
        STUNS.onTick();
        for (CustomWeapon weapon : Weapons.ALL) {
            weapon.onTick(server, config);
        }
        PROJECTILES.onTick(server, config);
        ANIMATIONS.onTick(server);
        EFFECTS.onTick(server);
        ALTARS.onTick(server, config);
        int interval = Math.max(1, config.stat_sweep_interval_ticks);
        if (++sweepTick % interval == 0) {
            Weapons.sweep(server, config, generation, CLAIMS);
        }
    }

    // ---------------------------------------------------------------------- events

    private InteractionResult onUseItem(net.minecraft.world.entity.player.Player player,
                                        net.minecraft.world.level.Level level,
                                        net.minecraft.world.InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(Items.MILK_BUCKET)) {
            BLEED.clear(serverPlayer.getUUID());
        }
        CustomWeapon weapon = Weapons.of(stack, config);
        if (weapon == null) {
            return InteractionResult.PASS;
        }
        return weapon.onRightClick(serverPlayer, stack, config);
    }

    /** Right-click on a block: only a recorded altar responds. */
    private InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player,
                                         Level level,
                                         net.minecraft.world.InteractionHand hand,
                                         net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Altars.Site site = ALTARS.siteAt(hit.getBlockPos());
        if (site == null) {
            return InteractionResult.PASS;
        }
        ALTARS.use(serverPlayer, site, config, generation);
        return InteractionResult.SUCCESS_SERVER;
    }

    private boolean allowDamage(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source,
                                float amount) {
        if (source.is(DamageTypeTags.IS_FALL)
                && entity instanceof ServerPlayer player
                && STATE.isFallImmune(player)) {
            return false;
        }
        return true;
    }

    private void afterDamage(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source,
                             float baseDamage, float damageTaken, boolean blocked) {
        // Ability damage is dealt through Hurt, which fires this event again. Without this
        // guard a Bloodletter's bleed tick would re-apply bleed, refresh its own timer and
        // never stop.
        if (Hurt.isAbilityDamage() || blocked || damageTaken <= 0) {
            return;
        }
        if (source.getDirectEntity() instanceof AbstractArrow arrow) {
            if (SHOCK.isArmed(arrow)) {
                SHOCK.onArrowHit(entity, arrow, config);
                return;
            }
            CustomWeapon armed = PROJECTILES.take(arrow);
            if (armed != null) {
                armed.onProjectileHit(arrow.getOwner() instanceof ServerPlayer shooter ? shooter : null,
                        entity, arrow, config);
                return;
            }
        }
        if (!(source.getDirectEntity() instanceof ServerPlayer attacker) || attacker == entity) {
            return;
        }
        ItemStack held = attacker.getMainHandItem();
        CustomWeapon weapon = Weapons.of(held, config);
        if (weapon != null) {
            weapon.onHit(attacker, entity, held, damageTaken, config);
        }
    }

    private void onArrowFired(AbstractArrow arrow, net.minecraft.server.level.ServerLevel level) {
        if (!(arrow.getOwner() instanceof ServerPlayer shooter)) {
            return;
        }
        // The arrow knows what fired it (a bow or crossbow) or what it is (a thrown
        // trident); the hands are only a fallback, since a trident has already left the
        // hand by the time this runs in some code paths.
        CustomWeapon weapon = Weapons.of(arrow.getWeaponItem(), config);
        if (weapon == null) {
            weapon = Weapons.of(arrow.getPickupItemStackOrigin(), config);
        }
        if (weapon == null) {
            weapon = Weapons.of(shooter.getMainHandItem(), config);
        }
        if (weapon == null) {
            weapon = Weapons.of(shooter.getOffhandItem(), config);
        }
        if (weapon != null) {
            weapon.onArrowFired(shooter, arrow, level, config);
        }
    }

    // -------------------------------------------------------------------- commands

    /**
     * {@code /customweapon unclaim <weapon>} lets a weapon be made again.
     *
     * <p>For the legendary that went into a lava pit with its owner. Without this the item is
     * simply gone from the world and no amount of wither skulls brings it back.
     */
    private com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> unclaimChoices() {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("weapon");
        for (CustomWeapon weapon : Weapons.ALL) {
            root.then(Commands.literal(weapon.id()).executes(context -> {
                Claims.Claim released = CLAIMS.release(weapon.id());
                if (released == null) {
                    context.getSource().sendFailure(Component.literal("")
                            .append(weapon.displayName())
                            .append(Component.literal(" has not been forged yet.")));
                    return 0;
                }
                context.getSource().sendSuccess(() -> Component.literal("Released ")
                        .append(weapon.displayName())
                        .append(Component.literal(" (was " + released.owner()
                                + "). It can be forged again, and any copy still in an "
                                + "inventory now counts as the real one.")
                                .withStyle(ChatFormatting.GRAY)), true);
                return 1;
            }));
        }
        return root;
    }

    /** {@code /customweapon altar <weapon>} builds one where you stand, for testing. */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> altarChoices() {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("place");
        for (CustomWeapon weapon : Weapons.ALL) {
            root.then(Commands.literal(weapon.id()).executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                Altars.Site site = ALTARS.placeAt((net.minecraft.server.level.ServerLevel) player.level(), player.blockPosition(), weapon);
                context.getSource().sendSuccess(() -> Component.literal("Altar placed at "
                        + site.x() + " " + site.y() + " " + site.z() + " forging ")
                        .append(weapon.displayName()), true);
                return 1;
            }));
        }
        return root;
    }

    /** {@code /customweapon altar seed [radius]}: builds the altars a pre-generated world never grew. */
    private int seedAltars(CommandSourceStack source, int radius) {
        ServerPlayer player = source.getPlayer();
        net.minecraft.core.BlockPos around = player != null ? player.blockPosition() : new net.minecraft.core.BlockPos(0, 64, 0);
        source.sendSuccess(() -> Component.literal("Seeding altars within " + radius + " blocks; this loads a chunk per region and can take a moment...")
                .withStyle(ChatFormatting.GRAY), true);
        int built = ALTARS.seed(source.getServer().overworld(), around, radius, config);
        source.sendSuccess(() -> Component.literal("Built " + built + " altar(s). " + ALTARS.count() + " known in total:")
                .withStyle(ChatFormatting.GREEN), true);
        for (Altars.Site site : ALTARS.all()) {
            CustomWeapon weapon = Weapons.byId(site.weapon());
            source.sendSuccess(() -> Component.literal(" - ")
                    .append(weapon == null ? Component.literal(site.weapon()) : weapon.displayName())
                    .append(Component.literal("  " + site.x() + " " + site.y() + " " + site.z()).withStyle(ChatFormatting.GRAY)), false);
        }
        return built;
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Open to everyone: these are the answers to the chat question on join.
        dispatcher.register(Commands.literal("weaponpack")
                .executes(context -> {
                    PACK.install(context.getSource().getPlayerOrException(), config);
                    return 1;
                })
                .then(Commands.literal("install").executes(context -> {
                    PACK.install(context.getSource().getPlayerOrException(), config);
                    return 1;
                }))
                .then(Commands.literal("later").executes(context -> {
                    PACK.later(context.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("never").executes(context -> {
                    PACK.never(context.getSource().getPlayerOrException());
                    return 1;
                })));

        // One literal per weapon, so the command tab-completes the ids without needing a
        // custom argument type registered on both sides of the connection.
        var targets = Commands.argument("targets", EntityArgument.players());
        for (CustomWeapon weapon : Weapons.ALL) {
            targets.then(Commands.literal(weapon.id()).executes(context -> {
                Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");
                if (config.unique_weapons && CLAIMS.isClaimed(weapon.id())) {
                    Claims.Claim claim = CLAIMS.claimOf(weapon.id());
                    context.getSource().sendFailure(Component.literal("")
                            .append(weapon.displayName())
                            .append(Component.literal(" already exists on this world, forged by "
                                    + claim.owner() + ". Use /customweapon unclaim "
                                    + weapon.id() + " first.")));
                    return 0;
                }
                String serial = config.unique_weapons ? Claims.newSerial() : null;
                for (ServerPlayer player : players) {
                    ItemStack stack = Weapons.create(weapon, config, generation, serial);
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
                context.getSource().sendSuccess(() -> Component.literal("Gave ")
                        .append(weapon.displayName())
                        .append(Component.literal(" to " + players.size() + " player(s)")
                                .withStyle(ChatFormatting.GRAY)), true);
                if (!weapon.enabled(config)) {
                    context.getSource().sendSuccess(() -> Component.literal(
                                    "Note: this weapon is disabled in the config, so it will behave as a plain item.")
                            .withStyle(ChatFormatting.YELLOW), false);
                }
                return players.size();
            }));
        }

        dispatcher.register(Commands.literal("customweapon")
                .requires(source -> source.permissions().hasPermission(OP))
                .then(Commands.literal("give").then(targets))
                .then(Commands.literal("altar")
                        .then(Commands.literal("find").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Altars.Site nearest = ALTARS.nearest(player.blockPosition());
                            if (nearest == null) {
                                context.getSource().sendFailure(Component.literal(
                                        "No altars have generated yet. They only appear in chunks generated after the mod was installed."));
                                return 0;
                            }
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Nearest altar: " + nearest.weapon() + " at "
                                            + nearest.x() + " " + nearest.y() + " " + nearest.z()), false);
                            return 1;
                        }))
                        .then(Commands.literal("seed")
                                .executes(context -> seedAltars(context.getSource(), 2500))
                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(100, 20000))
                                        .executes(context -> seedAltars(context.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius")))))
                        .then(Commands.literal("rebuild").executes(context -> {
                            int rebuilt = ALTARS.rebuildAll(context.getSource().getServer().overworld());
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Rebuilt " + rebuilt + " altar(s) to the current design."), true);
                            return rebuilt;
                        }))
                        .then(altarChoices()))
                .then(Commands.literal("claims").executes(context -> {
                    List<Claims.Claim> claims = CLAIMS.all();
                    if (claims.isEmpty()) {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Nothing has been forged on this world yet.")
                                .withStyle(ChatFormatting.GRAY), false);
                        return 0;
                    }
                    for (Claims.Claim claim : claims) {
                        CustomWeapon weapon = Weapons.byId(claim.weapon());
                        context.getSource().sendSuccess(() -> Component.literal(" - ")
                                .append(weapon == null
                                        ? Component.literal(claim.weapon())
                                        : weapon.displayName())
                                .append(Component.literal("  " + claim.owner()
                                        + "  #" + claim.serial())
                                        .withStyle(ChatFormatting.GRAY)), false);
                    }
                    return claims.size();
                }))
                .then(Commands.literal("unclaim").then(unclaimChoices()))
                .then(Commands.literal("reload").executes(context -> {
                    reloadConfig();
                    context.getSource().sendSuccess(() -> Component.literal(
                                    "Custom Weapons config reloaded; carried weapons will re-stat within a second.")
                            .withStyle(ChatFormatting.GREEN), true);
                    return 1;
                }))
                .then(Commands.literal("list").executes(context -> {
                    for (CustomWeapon weapon : Weapons.ALL) {
                        context.getSource().sendSuccess(() -> Component.literal(" - ")
                                .append(weapon.displayName())
                                .append(Component.literal(weapon.enabled(config) ? "" : "  (disabled)")
                                        .withStyle(ChatFormatting.DARK_GRAY)), false);
                    }
                    return Weapons.ALL.size();
                })));
    }

}
