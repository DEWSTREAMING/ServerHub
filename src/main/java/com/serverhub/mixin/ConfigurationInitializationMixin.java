package com.serverhub.mixin;

import com.serverhub.config.HubConfig;
import net.neoforged.neoforge.network.ConfigurationInitialization;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ConfigurationInitialization.class, remap = false)
public abstract class ConfigurationInitializationMixin {
    @Inject(
            method = "configureModdedClient(Lnet/neoforged/neoforge/network/event/RegisterConfigurationTasksEvent;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void serverhub$skipModdedClientChecks(
            RegisterConfigurationTasksEvent event,
            CallbackInfo ci) {
        if (HubConfig.get().isHubRole()) {
            ci.cancel();
        }
    }
}
