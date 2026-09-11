package dev.customweapons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Bloodletter's bleed.
 *
 * <p>Stacks to three, ticks twice a second for 1.0 per stack, so a fully stacked bleed is
 * 6.0 DPS on top of the sword's 4.0. The sword itself only hits for 2.0.
 *
 * <p>Each bleed carries a <strong>damage budget</strong>. Without it, a bleed whose timer is
 * refreshed by every hit never ends, and 6 damage a second with no cap kills a player in full
 * netherite in under four seconds from three swings of a 2-damage sword. The budget is what
 * keeps the weapon inside the balance rule; it refills on a fresh hit, so sustained pressure
 * is still rewarded, but the total is bounded.
 */
public final class BleedManager {

    private static final class Bleed {
        UUID owner;
        LivingEntity victim;
        int stacks;
        long expiresAt;
        double budget;
    }

    /** Keyed by victim: one bleed per victim, never two running in parallel. */
    private final Map<UUID, Bleed> active = new HashMap<>();
    private final Cooldowns clock;
    private int tick;

    public BleedManager(Cooldowns clock) {
        this.clock = clock;
    }

    public void apply(ServerPlayer attacker, LivingEntity victim, WeaponsConfig config) {
        if (victim instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return;
        }
        Bleed bleed = active.get(victim.getUUID());
        if (bleed == null) {
            bleed = new Bleed();
            active.put(victim.getUUID(), bleed);
        }
        // A second Bloodletter user takes over the existing bleed rather than starting a
        // parallel one, so two attackers cannot double the stack cap.
        bleed.owner = attacker.getUUID();
        bleed.victim = victim;
        bleed.stacks = Math.min(bleed.stacks + 1, Math.max(1, config.bleed_max_stacks));
        bleed.expiresAt = clock.tick() + config.bleed_duration_ticks;
        bleed.budget = config.bleed_damage_budget;

        if (config.log_abilities) {
            CustomWeapons.LOGGER.info("ABILITY bleed apply victim={} stacks={} owner={}",
                    victim.getName().getString(), bleed.stacks, attacker.getName().getString());
        }

        attacker.displayClientMessage(Component.literal(
                        "Bleed x" + bleed.stacks + "  (" + fmt(config.bleed_duration_ticks) + "s)")
                .withStyle(ChatFormatting.RED), true);

        if (victim.level() instanceof ServerLevel level) {
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.8f, 0.7f);
        }
    }

    public void onTick(MinecraftServer server, WeaponsConfig config) {
        int interval = Math.max(1, config.bleed_tick_interval_ticks);
        if (++tick % interval != 0) {
            return;
        }
        if (active.isEmpty()) {
            return;
        }
        long now = clock.tick();
        Iterator<Map.Entry<UUID, Bleed>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Bleed bleed = it.next().getValue();
            LivingEntity victim = bleed.victim;
            if (victim == null || !victim.isAlive() || victim.isRemoved()
                    || now >= bleed.expiresAt || bleed.budget <= 0
                    || !(victim.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            double amount = Math.min(bleed.stacks * config.bleed_damage_per_stack, bleed.budget);
            bleed.budget -= amount;

            ServerPlayer owner = server.getPlayerList().getPlayer(bleed.owner);
            // Credited to the owner so bleed kills produce a normal death message and give
            // the kill to the player who applied it, even after they log out mid-bleed.
            DamageSource source = owner != null
                    ? victim.damageSources().indirectMagic(owner, owner)
                    : victim.damageSources().magic();
            Hurt.deal(victim, source, (float) amount);
            if (config.log_abilities) {
                CustomWeapons.LOGGER.info("ABILITY bleed tick victim={} stacks={} amount={} budget_left={}",
                        victim.getName().getString(), bleed.stacks,
                        String.format("%.1f", amount), String.format("%.1f", bleed.budget));
            }

            level.sendParticles(new DustParticleOptions(0xFF0000, 1.0f),
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
                    6, 0.25, 0.35, 0.25, 0.0);
        }
    }

    /** What an Exsanguinate did: how many bleeds burst, the damage they landed, the stacks they carried. */
    public record Burst(int victims, double damage, int stacks) {
    }

    /**
     * Bursts every bleed this player owns within {@code radius}: the remaining budget lands
     * as one hit and the bleed is over.
     */
    public Burst burst(ServerPlayer owner, double radius, WeaponsConfig config) {
        int victims = 0;
        double damage = 0;
        int stacks = 0;
        Iterator<Map.Entry<UUID, Bleed>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Bleed bleed = it.next().getValue();
            LivingEntity victim = bleed.victim;
            if (!owner.getUUID().equals(bleed.owner) || victim == null || !victim.isAlive()
                    || victim.isRemoved() || victim.level() != owner.level()
                    || victim.distanceTo(owner) > radius) {
                continue;
            }
            double amount = Math.max(0, bleed.budget);
            it.remove();
            if (amount <= 0) {
                continue;
            }
            Hurt.deal(victim, victim.damageSources().indirectMagic(owner, owner), (float) amount);
            victims++;
            damage += amount;
            stacks += bleed.stacks;
            if (victim.level() instanceof ServerLevel level) {
                CustomWeapons.effects().play(level, "blood_burst", victim);
                level.sendParticles(new DustParticleOptions(0xFF0000, 1.5f),
                        victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
                        40, 0.4, 0.5, 0.4, 0.0);
                level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                        SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 0.5f);
            }
            if (config.log_abilities) {
                CustomWeapons.LOGGER.info("ABILITY bleed burst victim={} stacks={} amount={}",
                        victim.getName().getString(), bleed.stacks, String.format("%.1f", amount));
            }
        }
        return new Burst(victims, damage, stacks);
    }

    /** Milk, death and a dimension change all end a bleed. */
    public void clear(UUID victim) {
        active.remove(victim);
    }

    public void clear() {
        active.clear();
    }

    public int stacksOn(UUID victim) {
        Bleed bleed = active.get(victim);
        return bleed == null ? 0 : bleed.stacks;
    }

    private static String fmt(int ticks) {
        return String.format("%.1f", ticks / 20.0);
    }
}
