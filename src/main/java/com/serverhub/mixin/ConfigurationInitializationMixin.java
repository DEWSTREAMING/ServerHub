package com.serverhub.mixin;

import com.serverhub.config.HubConfig;
import com.serverhub.handshake.HubConfigSyncTask;
import java.util.function.Consumer;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.ConfigurationInitialization;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ConfigurationInitialization.class, remap = false)
public abstract class ConfigurationInitializationMixin {
    @Inject(
            method = "configureEarlyTasks(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void serverhub$skipEarlyRegistrySnapshotOnHub(
            ServerConfigurationPacketListener listener,
            Consumer<ConfigurationTask> tasks,
            CallbackInfo ci) {
        if (HubConfig.get().isHubRole()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "configureModdedClient(Lnet/neoforged/neoforge/network/event/RegisterConfigurationTasksEvent;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void serverhub$skipModdedClientChecks(
            RegisterConfigurationTasksEvent event,
            CallbackInfo ci) {
        if (HubConfig.get().isHubRole()) {
            ServerConfigurationPacketListener listener = event.getListener();
            if (listener.hasChannel(ConfigFilePayload.TYPE)) {
                event.register(new HubConfigSyncTask(listener));
            }
            ci.cancel();
        }
    }
}
