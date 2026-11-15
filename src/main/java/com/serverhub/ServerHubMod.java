package com.serverhub;

import com.mojang.logging.LogUtils;
import com.serverhub.chat.ChatMuteManager;
import com.serverhub.command.HubCommands;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ApothicEnchantingFallbackPayload;
import com.serverhub.handshake.ClientModTracker;
import com.serverhub.handshake.ManifestExporter;
import com.serverhub.handshake.RegistryManifests;
import com.serverhub.portal.HubPortalManager;
import com.serverhub.relay.RelayClient;
import com.serverhub.relay.RelayMessage;
import com.serverhub.relay.RelayServer;
import com.serverhub.relay.ServerStatusInfo;
import com.serverhub.transfer.TransferManager;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(ServerHubMod.MOD_ID)
public class ServerHubMod {
    public static final String MOD_ID = "serverhub";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile RelayServer relayServer;
    private static volatile RelayClient relayClient;
    private static volatile String cachedManifestJson;

    public ServerHubMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    public static RelayServer getRelayServer() {
        return relayServer;
    }

    public static RelayClient getRelayClient() {
        return relayClient;
    }

    public static Map<String, ServerStatusInfo> getKnownServers() {
        RelayClient client = relayClient;
        if (client != null) {
            return client.getKnownServers();
        }
        return Map.of();
    }

    public static ServerStatusInfo getServerStatus(String serverId) {
        RelayClient client = relayClient;
        if (client != null) {
            return client.getServerStatus(serverId);
        }
        return null;
    }

    public static ServerStatusInfo buildLocalStatus(MinecraftServer server, String status) {
        HubConfig cfg = HubConfig.get();
        int online = server.getPlayerList() != null ? server.getPlayerCount() : 0;
        int max = server.getPlayerList() != null ? server.getMaxPlayers() : 20;
        return new ServerStatusInfo(
                cfg.serverId,
                cfg.serverDisplayName,
                cfg.publicHost,
                cfg.publicPort,
                status,
                online,
                max,
                cfg.requiredModIds,
                System.currentTimeMillis());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        HubConfig cfg = HubConfig.reload();
        RegistryManifests.reload();
        ChatMuteManager.load();
        TransferManager.resetShutdownState();
        HubPortalManager.clearCooldownsForTesting();

        if (cfg.runRelayServer) {
            RelayServer rs = new RelayServer(cfg.relayPort, cfg.sharedSecret);
            rs.setManifestListener(
                    json -> server.execute(() -> RegistryManifests.upsertFromJson(json, true)));
            try {
                rs.start();
                relayServer = rs;
            } catch (IOException e) {
                LOGGER.error("Failed to start Server Hub relay on port {}", cfg.relayPort, e);
            }
        }

        RelayClient rc = new RelayClient(cfg.relayHost, cfg.relayPort, cfg.sharedSecret);
        rc.setHeartbeatSupplier(() -> buildLocalStatus(server, "ONLINE"));
        rc.setStatusChangeListener((info, prevStatus) -> server.execute(() -> handleStatusTransition(server, info, prevStatus)));
        rc.setChatListener(msg -> server.execute(() -> deliverCrossServerChat(server, msg)));
        rc.start();
        relayClient = rc;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        RelayClient rc = relayClient;
        if (!HubConfig.get().isHubRole()) {
            cachedManifestJson = ManifestExporter.exportJson(event.getServer());
            if (rc != null) {
                rc.setManifestSupplier(() -> cachedManifestJson);
                if (rc.isConnected()) {
                    rc.sendManifest(cachedManifestJson);
                }
            }
        }
        if (rc != null && rc.isConnected()) {
            rc.sendHeartbeat(buildLocalStatus(event.getServer(), "ONLINE"));
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        TransferManager.evacuatePlayersToHubOnShutdown(event.getServer());
        cachedManifestJson = null;

        RelayClient rc = relayClient;
        relayClient = null;
        if (rc != null) {
            rc.stop();
        }

        RelayServer rs = relayServer;
        relayServer = null;
        if (rs != null) {
            rs.stop();
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        HubCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        HubPortalManager.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        RelayClient rc = relayClient;
        if (rc == null) {
            return;
        }
        HubConfig cfg = HubConfig.get();
        ServerPlayer player = event.getPlayer();
        rc.sendChat(
                cfg.serverId,
                cfg.serverDisplayName,
                player.getUUID(),
                player.getGameProfile().getName(),
                event.getRawText());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!HubConfig.get().isHubRole()) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        if (overworld != null) {
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(
                    overworld,
                    spawn.getX() + 0.5D,
                    spawn.getY(),
                    spawn.getZ() + 0.5D,
                    overworld.getSharedSpawnAngle(),
                    0.0F);
        }
        if (ClientModTracker.getClientMods(player.getUUID()).contains("apothic_enchanting")
                && player.connection != null
                && player.connection.hasChannel(ApothicEnchantingFallbackPayload.ID)) {
            player.connection.send(new ClientboundCustomPayloadPacket(new ApothicEnchantingFallbackPayload()));
        }
        Optional<String> originId = ClientModTracker.getOriginServer(player.getUUID());
        if (originId.isPresent()) {
            ServerStatusInfo info = getServerStatus(originId.get());
            if (info != null && !info.isOnline()) {
                player.sendSystemMessage(Component.literal(
                                "Welcome to the Hub. " + info.displayName() + " is restarting; you will be notified when it is back online.")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        ClientModTracker.clearPlayer(uuid);
        HubPortalManager.clearPlayer(uuid);
    }

    private void handleStatusTransition(MinecraftServer server, ServerStatusInfo info, String previousStatus) {
        HubConfig cfg = HubConfig.get();
        if (!cfg.isHubRole() || info.serverId().equalsIgnoreCase(cfg.serverId)) {
            return;
        }
        if (server.getPlayerList() == null) {
            return;
        }

        if ("ONLINE".equalsIgnoreCase(info.status())
                && ("RESTARTING".equalsIgnoreCase(previousStatus) || "OFFLINE".equalsIgnoreCase(previousStatus))) {
            Component announcement = Component.literal(
                            "Server " + info.displayName() + " is back online! Type /join " + info.serverId() + " or use the portal.")
                    .withStyle(ChatFormatting.GREEN);
            server.getPlayerList().broadcastSystemMessage(announcement, false);
        } else if ("RESTARTING".equalsIgnoreCase(info.status())) {
            Component announcement = Component.literal(
                            "Server " + info.displayName() + " is restarting.")
                    .withStyle(ChatFormatting.YELLOW);
            server.getPlayerList().broadcastSystemMessage(announcement, false);
        }
    }

    private void deliverCrossServerChat(MinecraftServer server, RelayMessage msg) {
        if (server.getPlayerList() == null || msg.chatMessage == null) {
            return;
        }
        String localServerId = HubConfig.get().serverId;
        if (localServerId.equalsIgnoreCase(msg.sourceServerId)) {
            return;
        }
        String display = (msg.sourceDisplayName != null && !msg.sourceDisplayName.isBlank())
                ? msg.sourceDisplayName
                : msg.sourceServerId;
        String sender = msg.senderName != null ? msg.senderName : "Player";

        Component formatted = Component.literal("[" + display + "] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("<" + sender + "> ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(msg.chatMessage).withStyle(ChatFormatting.WHITE));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ChatMuteManager.shouldReceiveChat(player.getUUID(), localServerId, msg.sourceServerId)) {
                player.sendSystemMessage(formatted);
            }
        }
    }
}
