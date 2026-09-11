package dev.customweapons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Targets held fast: the Stormpiercer's shock and the Frostbrand's beam both root.
 *
 * <p>Slowness alone still lets a player creep and jump, so the server also puts them back
 * where they stood every tick until the stun ends; on a vanilla client that reads as being
 * held. Mobs are simply pinned.
 */
public final class Stuns {

    private record Stun(LivingEntity victim, Vec3 at, long until) {
    }

    private final List<Stun> stunned = new ArrayList<>();
    private final Cooldowns clock;

    public Stuns(Cooldowns clock) {
        this.clock = clock;
    }

    public void stun(LivingEntity victim, int ticks, String label) {
        if (ticks <= 0) {
            return;
        }
        stunned.removeIf(s -> s.victim() == victim);
        stunned.add(new Stun(victim, victim.position(), clock.tick() + ticks));
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 6, false, false));
        victim.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, ticks, 2, false, false));
        victim.setDeltaMovement(Vec3.ZERO);
        victim.hurtMarked = true;
        if (victim instanceof ServerPlayer player) {
            player.displayClientMessage(Component.literal(label).withStyle(ChatFormatting.AQUA), true);
        }
    }

    public void onTick() {
        if (stunned.isEmpty()) {
            return;
        }
        long now = clock.tick();
        Iterator<Stun> it = stunned.iterator();
        while (it.hasNext()) {
            Stun stun = it.next();
            LivingEntity victim = stun.victim();
            if (now >= stun.until() || victim.isRemoved() || !victim.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 at = stun.at();
            if (victim.position().distanceToSqr(at) > 0.0025) {
                victim.teleportTo(at.x, at.y, at.z);
            }
            victim.setDeltaMovement(Vec3.ZERO);
            victim.hurtMarked = true;
        }
    }

    public void clear() {
        stunned.clear();
    }
}
