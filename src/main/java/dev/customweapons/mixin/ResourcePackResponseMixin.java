package dev.customweapons.mixin;

import dev.customweapons.CustomWeapons;
import dev.customweapons.PackOffer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kicks a player who refuses the required pack.
 *
 * <p>Vanilla only disconnects a decliner when the pack came from server.properties; a
 * pack a mod pushed with required=true gets the dialog but no consequence. This supplies
 * the consequence, for our pack only, so another mod's optional pack is never affected.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ResourcePackResponseMixin {

    @Shadow @Final protected MinecraftServer server;

    @Shadow public abstract void disconnect(Component reason);

    @Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
    private void customweapons$onResponse(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        if (!PackOffer.isRefusal(packet.id(), packet.action(), CustomWeapons.config())) {
            return;
        }
        // This runs on the network thread; the kick belongs on the server thread.
        String reason = CustomWeapons.config().pack_kick_message;
        server.execute(() -> {
            CustomWeapons.LOGGER.info("PACK refused ({}) - disconnecting", packet.action());
            disconnect(Component.literal(reason));
        });
    }
}
