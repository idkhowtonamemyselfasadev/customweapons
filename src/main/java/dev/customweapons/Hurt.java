package dev.customweapons;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Damage helper for ability damage. */
public final class Hurt {

    private static boolean inAbilityDamage;

    private Hurt() {
    }

    /**
     * True while an ability's own damage is being applied.
     *
     * <p>Ability damage goes through the same damage pipeline as a sword swing, so it fires
     * the same events. The hit handlers check this and stay out of the way; otherwise a bleed
     * tick counts as a Bloodletter hit, refreshes its own bleed and never ends. Everything
     * here runs on the server thread, so a plain flag is enough.
     */
    public static boolean isAbilityDamage() {
        return inAbilityDamage;
    }

    /**
     * Deals damage that ignores the victim's invulnerability frames.
     *
     * <p>Vanilla gives an entity 20 ticks of invulnerability after a hit. Bleed ticks every
     * 10, and the shock chain lands in the same tick as the arrow, so without this every
     * other ability tick would be silently swallowed.
     *
     * <p>The old value is put back afterwards rather than left at zero: zeroing it and
     * walking away would also hand the attacker a free extra melee swing.
     */
    public static boolean deal(LivingEntity victim, DamageSource source, float amount) {
        if (!(victim.level() instanceof ServerLevel level) || !victim.isAlive()) {
            return false;
        }
        int saved = victim.invulnerableTime;
        victim.invulnerableTime = 0;
        inAbilityDamage = true;
        try {
            return victim.hurtServer(level, source, amount);
        } finally {
            inAbilityDamage = false;
            victim.invulnerableTime = saved;
        }
    }
}
