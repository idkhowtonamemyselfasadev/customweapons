package dev.customweapons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The shared shape of every weapon's second ability, the <b>ultimate</b>: sneak and
 * left-click with the weapon in hand.
 *
 * <p>Each one hits everything around the player and each is different, but they share the
 * rules: a long cooldown (up to a minute), and damage that is <b>true damage</b> - dealt
 * as the warden's sonic boom, the one vanilla damage type that ignores both armour and
 * Protection - so "six hearts" means six hearts against Protection IV netherite, not the
 * one and a half that would survive the armour maths. Resistance and absorption still
 * count, and Ranks' PvP rules still see the player as the attacker.
 */
public final class Ultimate {

    private Ultimate() {
    }

    /** Whether the ultimate may fire now; tells the player the wait if not. */
    public static boolean ready(ServerPlayer player, String key, String name) {
        int remaining = CustomWeapons.cooldowns().remaining(player, key);
        if (remaining <= 0) {
            return true;
        }
        player.displayClientMessage(Component.literal(String.format("%s  %.0fs", name, remaining / 20.0))
                .withStyle(ChatFormatting.GRAY), true);
        return false;
    }

    /** Everything living around the player that the ultimate may hit. */
    public static List<LivingEntity> targets(ServerPlayer player, double radius) {
        return Targeting.around((ServerLevel) player.level(), player, player.position(), radius);
    }

    /** True damage, credited to the player. */
    public static boolean strike(ServerPlayer player, LivingEntity victim, double amount) {
        return Hurt.deal(victim, player.damageSources().sonicBoom(player), (float) amount);
    }

    /** Shoves a target away from (or, with a negative power, towards) the player. */
    public static void fling(ServerPlayer player, LivingEntity victim, double power, double lift) {
        Vec3 away = victim.position().subtract(player.position());
        away = new Vec3(away.x, 0, away.z);
        Vec3 dir = away.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : away.normalize();
        victim.setDeltaMovement(dir.x * power, lift, dir.z * power);
        victim.hurtMarked = true;
        if (victim instanceof ServerPlayer hit) {
            hit.connection.send(new ClientboundSetEntityMotionPacket(hit));
        }
    }

    /** Wraps up: cooldown, second animation, sound, message, log. */
    public static void fired(ServerPlayer player, CustomWeapon weapon, String key, int cooldownTicks,
                             String name, int victims, SoundEvent sound, float pitch, WeaponsConfig config) {
        CustomWeapons.cooldowns().set(player, key, cooldownTicks, player.getMainHandItem());
        CustomWeapons.animations().playUltimate(player, weapon, config);
        if (player.level() instanceof ServerLevel level && sound != null) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 1.2f, pitch);
        }
        player.displayClientMessage(Component.literal(name + (victims > 0 ? "  " + victims + " hit" : "  nothing in range"))
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ULTIMATE {} player={} victims={}", key, player.getName().getString(), victims);
        }
    }
}
