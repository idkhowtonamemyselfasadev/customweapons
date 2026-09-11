package dev.customweapons.mixin;

import dev.customweapons.CustomWeapons;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A target frozen by the Ice Beam does not move. Vanilla applies the knockback of every
 * hit before the freeze can put the target back, so it twitches with each blow and the
 * killing blow sends the body flying out of the ice; cancelling the knockback at its
 * source keeps it exactly where it was frozen. The Stormpiercer's stun is not this
 * rigid: a shocked target still takes knockback.
 */
@Mixin(LivingEntity.class)
public abstract class KnockbackMixin {

    @Inject(method = "knockback(DDD)V", at = @At("HEAD"), cancellable = true)
    private void customweapons$noKnockbackWhileHeld(double strength, double x, double z, CallbackInfo ci) {
        if (CustomWeapons.stuns().isRigid((LivingEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
