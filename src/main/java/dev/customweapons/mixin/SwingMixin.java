package dev.customweapons.mixin;

import dev.customweapons.CustomWeapons;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The ultimate's trigger: a left-click while sneaking.
 *
 * <p>A left-click always sends a swing packet, whether or not it hit anything, so this is
 * the one place every sneak + left-click passes through. The swing itself (and any hit it
 * carried) still happens; the ultimate fires on top of it. The handler runs on the server
 * thread after the packet has been moved there, so world access is safe.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SwingMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleAnimate(Lnet/minecraft/network/protocol/game/ServerboundSwingPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"))
    private void customweapons$ultimate(ServerboundSwingPacket packet, CallbackInfo ci) {
        if (packet.getHand() == InteractionHand.MAIN_HAND && player.isShiftKeyDown()) {
            CustomWeapons.onSneakSwing(player);
        }
    }
}
