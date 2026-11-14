package com.serverhub.mixin;

import com.serverhub.config.HubConfig;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips sending {@link ClientboundRecipePacket} on Hub servers because clients with modded
 * {@code RecipeBookType} extensions read more enum booleans than a vanilla Hub server writes.
 */
@Mixin(ServerRecipeBook.class)
public class ServerRecipeBookMixin {
    @Inject(method = "sendInitialRecipeBook", at = @At("HEAD"), cancellable = true)
    private void serverhub$skipInitialRecipeBookOnHub(ServerPlayer player, CallbackInfo ci) {
        if (HubConfig.get().isHubRole()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendRecipes", at = @At("HEAD"), cancellable = true)
    private void serverhub$skipSendRecipesOnHub(
            ClientboundRecipePacket.State state,
            ServerPlayer player,
            List<ResourceLocation> recipes,
            CallbackInfo ci) {
        if (HubConfig.get().isHubRole()) {
            ci.cancel();
        }
    }
}
