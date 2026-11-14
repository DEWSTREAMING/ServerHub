package com.serverhub.handshake;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.configuration.SyncConfig;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;

/**
 * Sends Hub server configs and empty fallback server configs for client mods so modded clients
 * initialize their {@code ModConfig.Type.SERVER} specs with defaults when joining the Hub.
 */
public record HubConfigSyncTask(ServerConfigurationPacketListener listener) implements ICustomConfigurationTask {
    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        Set<String> sentFiles = new HashSet<>();
        for (ConfigFilePayload payload : ConfigSync.syncConfigs()) {
            sentFiles.add(payload.fileName());
            sender.accept(payload);
        }

        Set<String> extraConfigFiles = new LinkedHashSet<>(List.of(
                "neoforge-server.toml",
                "curios-server.toml",
                "holeydigsite-server.toml",
                "holeydigsite-crates.toml"));
        if (listener instanceof ServerCommonPacketListenerImpl commonListener
                && commonListener.getOwner() != null
                && commonListener.getOwner().getId() != null) {
            for (String modId : ClientModTracker.getClientMods(commonListener.getOwner().getId())) {
                if (modId != null && !modId.isBlank()) {
                    extraConfigFiles.add(modId + "-server.toml");
                }
            }
        }

        for (String fileName : extraConfigFiles) {
            if (sentFiles.add(fileName)) {
                sender.accept(new ConfigFilePayload(fileName, new byte[0]));
            }
        }

        listener.finishCurrentTask(type());
    }

    @Override
    public Type type() {
        return SyncConfig.TYPE;
    }
}
