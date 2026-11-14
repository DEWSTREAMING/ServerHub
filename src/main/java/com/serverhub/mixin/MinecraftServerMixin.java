package com.serverhub.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class MinecraftServerMixin {
    @Inject(method = "acceptsTransfers()Z", at = @At("HEAD"), cancellable = true)
    private void serverhub$alwaysAcceptTransfers(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
