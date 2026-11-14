package com.serverhub.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DedicatedServer.class, remap = false)
public abstract class DedicatedServerMixin {
    @Inject(method = "acceptsTransfers()Z", at = @At("HEAD"), cancellable = true)
    private void serverhub$alwaysAcceptTransfers(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
