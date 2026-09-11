package dev.customweapons.mixin;

import dev.customweapons.Altars;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** No explosion - TNT, creeper, a Hellfire bolt, a bed - takes a block out of a temple. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void customweapons$keepTemples(List<BlockPos> positions, CallbackInfo ci) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        positions.removeIf(pos -> Altars.guards(self.level(), pos));
    }
}
