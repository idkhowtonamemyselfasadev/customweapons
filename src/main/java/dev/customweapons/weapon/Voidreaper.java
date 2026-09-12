package dev.customweapons.weapon;

import dev.customweapons.CustomWeapon;
import dev.customweapons.CustomWeapons;
import dev.customweapons.Hurt;
import dev.customweapons.ItemCost;
import dev.customweapons.Stats;
import dev.customweapons.Targeting;
import dev.customweapons.Weapons;
import dev.customweapons.WeaponsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import dev.customweapons.Ultimate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Voidreaper - a netherite hoe swung as a scythe.
 *
 * <p>Slow and heavy, it withers what it cuts and feeds on what it kills. The Rift is the
 * trick: the wielder steps through the void to appear at the back of whatever they are
 * looking at, and the first cut after that is a backstab.
 */
public final class Voidreaper extends CustomWeapon {

    public static final String RIFT = "rift";

    /** Player -> the tick their backstab window closes. */
    private final Map<UUID, Long> backstab = new HashMap<>();

    @Override
    public String id() {
        return "voidreaper";
    }

    @Override
    public Item baseItem() {
        return Items.NETHERITE_HOE;
    }

    @Override
    public boolean enabled(WeaponsConfig config) {
        return config.voidreaper_enabled;
    }

    @Override
    public Component displayName() {
        return Component.literal("Voidreaper")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_PURPLE)
                        .withBold(true).withItalic(false));
    }

    @Override
    public List<Component> lore(WeaponsConfig config) {
        return List.of(
                Weapons.loreLine(String.format("Rift: right-click to step through the void behind whatever is %.0f blocks ahead",
                        config.rift_range)),
                Weapons.loreLine(String.format("Backstab: your next hit within %.0fs deals +%.1f, cooldown %.0fs",
                        config.backstab_window_ticks / 20.0, config.backstab_bonus_damage,
                        config.rift_cooldown_ticks / 20.0)),
                Weapons.loreLine(String.format("Wither: every hit withers for %.0fs", config.reap_wither_ticks / 20.0)),
                Weapons.loreLine(String.format("Soul Harvest: a kill heals you %.1f and shields you for %.0fs",
                        config.harvest_heal, config.harvest_absorption_ticks / 20.0)),
                Weapons.loreLine("Takes sword enchantments: Sharpness V and the rest"));
    }

    @Override
    public List<ItemCost> ingredients() {
        return List.of(
                new ItemCost(Items.NETHERITE_HOE, 1),
                new ItemCost(Items.CRYING_OBSIDIAN, 4),
                new ItemCost(Items.ECHO_SHARD, 3),
                new ItemCost(Items.SCULK_CATALYST, 1));
    }

    @Override
    public Block altarPillar() {
        return Blocks.CRYING_OBSIDIAN;
    }

    @Override
    public Block altarAccent() {
        return Blocks.SCULK;
    }

    @Override
    public ItemAttributeModifiers attributes(WeaponsConfig config) {
        return Stats.melee(id(), config.voidreaper_attack_damage, config.voidreaper_attack_speed);
    }

    @Override
    public InteractionResult onRightClick(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        int remaining = CustomWeapons.cooldowns().remaining(player, RIFT);
        if (remaining > 0) {
            player.displayClientMessage(Component.literal(String.format("Rift  %.1fs", remaining / 20.0))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        Targeting.Ray ray = Targeting.look(player, level, config.rift_range);
        LivingEntity mark = ray.target();
        if (mark == null) {
            player.displayClientMessage(Component.literal("Rift  no target").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        Vec3 dest = landing(level, player, mark);
        if (dest == null) {
            player.displayClientMessage(Component.literal("Rift  no room behind it").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.PASS;
        }
        Vec3 from = player.position();
        CustomWeapons.cooldowns().set(player, RIFT, config.rift_cooldown_ticks, weapon);
        backstab.put(player.getUUID(), CustomWeapons.cooldowns().tick() + config.backstab_window_ticks);

        // The tear where they left, then the tear where they arrive.
        CustomWeapons.effects().play(level, "rift_open", from, null);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0, from.z, 40, 0.4, 0.8, 0.4, 0.05);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 0.6f);

        Vec3 face = mark.position().add(0, mark.getEyeHeight() * 0.8, 0).subtract(dest.add(0, player.getEyeHeight(), 0));
        float yaw = (float) (Math.toDegrees(Math.atan2(face.z, face.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(face.y, Math.sqrt(face.x * face.x + face.z * face.z)));
        player.teleportTo(level, dest.x, dest.y, dest.z, Set.of(), yaw, pitch, true);
        player.resetFallDistance();

        CustomWeapons.effects().play(level, "rift_open", dest, null);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, dest.x, dest.y + 1.0, dest.z, 40, 0.4, 0.8, 0.4, 0.05);
        level.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 0.8f);
        level.playSound(null, dest.x, dest.y, dest.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.PLAYERS, 0.3f, 1.6f);
        CustomWeapons.animations().play(player, this, config);

        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY rift player={} victim={}",
                    player.getName().getString(), mark.getName().getString());
        }
        player.displayClientMessage(Component.literal("Rift  behind ").append(mark.getName())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return InteractionResult.SUCCESS;
    }

    /**
     * A spot 1.5 blocks behind the mark that a player fits into. Behind means the way the
     * mark is facing; a mark with no facing to speak of (a squid, a hanging bat) is taken
     * from the far side as seen from the player. Failing that, either side, then the near
     * side. Null if the mark is boxed in.
     */
    private static Vec3 landing(ServerLevel level, ServerPlayer player, LivingEntity mark) {
        Vec3 facing = mark.getLookAngle().multiply(1, 0, 1);
        if (facing.lengthSqr() < 1.0e-3) {
            facing = mark.position().subtract(player.position()).multiply(1, 0, 1);
        }
        if (facing.lengthSqr() < 1.0e-3) {
            facing = new Vec3(0, 0, 1);
        }
        facing = facing.normalize();
        Vec3 side = new Vec3(-facing.z, 0, facing.x);
        Vec3[] tries = {
                facing.scale(-1.5), side.scale(1.5), side.scale(-1.5), facing.scale(1.5),
        };
        for (Vec3 offset : tries) {
            Vec3 spot = mark.position().add(offset);
            // Let it settle up to one block down or up onto a step.
            for (double dy : new double[]{0, -1, 1}) {
                Vec3 at = spot.add(0, dy, 0);
                AABB box = player.getBoundingBox().move(at.subtract(player.position()));
                boolean floor = !level.noCollision(box.move(0, -0.1, 0).setMaxY(box.minY));
                if (level.noCollision(player, box) && floor) {
                    return at;
                }
            }
        }
        return null;
    }

    @Override
    public void onHit(ServerPlayer attacker, LivingEntity victim, ItemStack weapon,
                      float damageDealt, WeaponsConfig config) {
        CustomWeapons.animations().play(attacker, this, config);
        victim.addEffect(new MobEffectInstance(MobEffects.WITHER, config.reap_wither_ticks, 0));
        ServerLevel level = attacker.level() instanceof ServerLevel l ? l : null;
        if (level != null) {
            level.sendParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5,
                    victim.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
        }

        Long until = backstab.get(attacker.getUUID());
        if (until != null && until > CustomWeapons.cooldowns().tick()) {
            backstab.remove(attacker.getUUID());
            Hurt.deal(victim, victim.damageSources().indirectMagic(attacker, attacker),
                    (float) config.backstab_bonus_damage);
            if (level != null) {
                level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                        SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 0.5f);
                level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6,
                        victim.getZ(), 20, 0.3, 0.3, 0.3, 0.2);
            }
            if (config.log_abilities) {
                CustomWeapons.LOGGER.info("ABILITY backstab player={} victim={} bonus={}",
                        attacker.getName().getString(), victim.getName().getString(),
                        String.format("%.1f", config.backstab_bonus_damage));
            }
            attacker.displayClientMessage(Component.literal(
                            String.format("Backstab  +%.1f", config.backstab_bonus_damage))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
    }

    @Override
    public void onKill(ServerPlayer killer, LivingEntity victim, WeaponsConfig config) {
        harvest(killer, victim, config);
    }

    private void harvest(ServerPlayer player, LivingEntity victim, WeaponsConfig config) {
        if (config.harvest_heal > 0) {
            player.heal((float) config.harvest_heal);
        }
        if (config.harvest_absorption_ticks > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, config.harvest_absorption_ticks, 0));
        }
        if (player.level() instanceof ServerLevel level) {
            CustomWeapons.effects().play(level, "soul_harvest", victim.position(), null);
            level.sendParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + 0.5, victim.getZ(),
                    20, 0.3, 0.5, 0.3, 0.05);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.0f, 0.8f);
        }
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY harvest player={} victim={} heal={}",
                    player.getName().getString(), victim.getName().getString(),
                    String.format("%.1f", config.harvest_heal));
        }
        player.displayClientMessage(Component.literal(String.format("Soul Harvest  +%.1f", config.harvest_heal))
                .withStyle(ChatFormatting.DARK_PURPLE), true);
    }

    @Override
    public void forget(UUID player) {
        backstab.remove(player);
    }

    // ---- ultimate: Void Collapse -----------------------------------------------------------------
    public static final String VOID_COLLAPSE = "void_collapse";

    @Override
    public void onUltimate(ServerPlayer player, ItemStack weapon, WeaponsConfig config) {
        if (!Ultimate.ready(player, VOID_COLLAPSE, "Void Collapse")) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Ultimate.spin(player, 12);
        int hit = 0;
        for (LivingEntity victim : Ultimate.targets(player, config.void_collapse_radius)) {
            // Torn to the player's feet, then the void takes its due.
            Vec3 to = player.position().add(player.getLookAngle().normalize().scale(1.5));
            if (victim instanceof ServerPlayer sp) {
                sp.teleportTo(level, to.x, to.y, to.z, Set.of(), sp.getYRot(), sp.getXRot(), false);
            } else {
                victim.teleportTo(to.x, to.y, to.z);
            }
            if (Ultimate.strike(player, victim, config.void_collapse_damage)) {
                hit++;
            }
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, config.void_collapse_wither_ticks, 1));
            CustomWeapons.effects().play(level, "rift_open", victim);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 200, 3.5, 1, 3.5, 0.3);
        Ultimate.fired(player, this, VOID_COLLAPSE, config.void_collapse_cooldown_ticks, "Void Collapse", hit, SoundEvents.ENDERMAN_TELEPORT, 0.4f, config);
    }

    @Override
    public String ultimateLore(WeaponsConfig config) {
        return String.format("Void Collapse - everything within %.0f blocks is torn to your feet, withered and takes %.1f true damage. %ds", config.void_collapse_radius, config.void_collapse_damage, config.void_collapse_cooldown_ticks / 20);
    }
}
