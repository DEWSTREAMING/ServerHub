package com.serverhub.mixin;

import com.serverhub.config.HubConfig;
import javax.annotation.Nullable;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses recipe sync packets on Hub servers so modpack clients do not trigger client-side
 * recipe indexing plugins or mismatched extended {@code RecipeBookType} decoding before leaving the Hub.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerMixin {
    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void serverhub$skipRecipePacketsOnHub(
            Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (HubConfig.get().isHubRole()
                && (packet instanceof ClientboundUpdateRecipesPacket
                        || packet instanceof ClientboundRecipePacket)) {
            ci.cancel();
        }
    }
}
