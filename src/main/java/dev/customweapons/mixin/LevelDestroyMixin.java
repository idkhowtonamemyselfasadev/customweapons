package dev.customweapons.mixin;

import dev.customweapons.Altars;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs that break blocks cannot break a temple. */
@Mixin(Level.class)
public abstract class LevelDestroyMixin {

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void customweapons$keepTemples(BlockPos pos, boolean drop, Entity breaker, int depth,
                                           CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level && Altars.guards(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
