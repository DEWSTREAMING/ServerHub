package com.serverhub;

import com.mojang.logging.LogUtils;
import com.serverhub.chat.ChatMuteManager;
import com.serverhub.command.HubCommands;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ClientModTracker;
import com.serverhub.handshake.ManifestExporter;
import com.serverhub.portal.HubPortalManager;
import com.serverhub.relay.RelayClient;
import com.serverhub.relay.RelayMessage;
import com.serverhub.relay.RelayServer;
import com.serverhub.relay.ServerStatusInfo;
import com.serverhub.transfer.TransferManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
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
        ChatMuteManager.load();
        TransferManager.resetShutdownState();
        HubPortalManager.clearCooldownsForTesting();

        if (cfg.runRelayServer) {
            RelayServer rs = new RelayServer(cfg.relayPort, cfg.sharedSecret);
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
        rc.setStatusSnapshotListener(() -> server.execute(() -> updateTabListForAll(server, null, false)));
        rc.setChatListener(msg -> server.execute(() -> deliverCrossServerChat(server, msg)));
        rc.setPresenceListener(msg -> server.execute(() -> deliverCrossServerPresence(server, msg)));
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
                player.getGameProfile().name(),
                event.getRawText());
    }

    private static String lastTabSignature = "";

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer mcServer = player.level().getServer();
        HubConfig cfg = HubConfig.get();
        RelayClient rc = relayClient;
        if (rc != null) {
            rc.sendPlayerPresence(
                    cfg.serverId,
                    cfg.serverDisplayName,
                    player.getUUID(),
                    player.getGameProfile().name(),
                    true);
            if (mcServer != null) {
                rc.sendHeartbeat(buildLocalStatus(mcServer, "ONLINE"));
            }
        }
        if (mcServer != null) {
            updateTabListForAll(mcServer, null, true);
        }
        if (!cfg.isHubRole()) {
            return;
        }
        ServerLevel overworld = mcServer != null ? mcServer.overworld() : null;
        if (overworld != null) {
            BlockPos spawn = overworld.getRespawnData().pos();
            player.teleportTo(
                    overworld,
                    spawn.getX() + 0.5D,
                    spawn.getY(),
                    spawn.getZ() + 0.5D,
                    Set.of(),
                    overworld.getRespawnData().yaw(),
                    0.0F,
                    false);
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
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer mcServer = player.level().getServer();
            HubConfig cfg = HubConfig.get();
            RelayClient rc = relayClient;
            if (rc != null) {
                rc.sendPlayerPresence(
                        cfg.serverId,
                        cfg.serverDisplayName,
                        uuid,
                        player.getGameProfile().name(),
                        false);
                if (mcServer != null) {
                    int remaining = Math.max(0, mcServer.getPlayerCount() - 1);
                    rc.sendHeartbeat(new ServerStatusInfo(
                            cfg.serverId,
                            cfg.serverDisplayName,
                            cfg.publicHost,
                            cfg.publicPort,
                            "ONLINE",
                            remaining,
                            mcServer.getMaxPlayers(),
                            cfg.requiredModIds,
                            System.currentTimeMillis()));
                }
            }
            if (mcServer != null) {
                updateTabListForAll(mcServer, uuid, true);
            }
        }
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

    private void deliverCrossServerPresence(MinecraftServer server, RelayMessage msg) {
        if (server.getPlayerList() == null || msg.senderName == null || msg.senderName.isBlank()) {
            return;
        }
        String localServerId = HubConfig.get().serverId;
        if (localServerId.equalsIgnoreCase(msg.sourceServerId)) {
            return;
        }
        String display = (msg.sourceDisplayName != null && !msg.sourceDisplayName.isBlank())
                ? msg.sourceDisplayName
                : msg.sourceServerId;
        String action = msg.joined ? " joined" : " left";

        Component formatted = Component.literal("[" + display + "] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(msg.senderName + action).withStyle(ChatFormatting.YELLOW));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ChatMuteManager.shouldReceiveJoinLeave(player.getUUID(), localServerId, msg.sourceServerId)) {
                player.sendSystemMessage(formatted);
            }
        }
    }

    private void updateTabListForAll(
            MinecraftServer server, UUID excludingUuid, boolean force) {
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        HubConfig cfg = HubConfig.get();
        List<ServerStatusInfo> servers = new ArrayList<>(getKnownServers().values());
        int localCount = server.getPlayerCount();
        if (excludingUuid != null) {
            localCount = Math.max(0, localCount - 1);
        }
        boolean hasLocal = false;
        for (int i = 0; i < servers.size(); i++) {
            ServerStatusInfo s = servers.get(i);
            if (s.serverId().equalsIgnoreCase(cfg.serverId)) {
                servers.set(i, new ServerStatusInfo(
                        s.serverId(),
                        cfg.serverDisplayName,
                        s.host(),
                        s.port(),
                        "ONLINE",
                        localCount,
                        server.getMaxPlayers(),
                        s.requiredMods(),
                        System.currentTimeMillis()));
                hasLocal = true;
                break;
            }
        }
        if (!hasLocal) {
            servers.add(new ServerStatusInfo(
                    cfg.serverId,
                    cfg.serverDisplayName,
                    cfg.publicHost,
                    cfg.publicPort,
                    "ONLINE",
                    localCount,
                    server.getMaxPlayers(),
                    cfg.requiredModIds,
                    System.currentTimeMillis()));
        }

        servers.sort(Comparator.comparing((ServerStatusInfo s) -> !s.serverId().equalsIgnoreCase("hub"))
                .thenComparing(ServerStatusInfo::displayName, String.CASE_INSENSITIVE_ORDER));

        StringBuilder sig = new StringBuilder();
        MutableComponent footer = Component.empty();
        for (int i = 0; i < servers.size(); i++) {
            ServerStatusInfo s = servers.get(i);
            if (i > 0) {
                footer.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY));
                sig.append('|');
            }
            boolean online = s.isOnline();
            footer.append(Component.literal(s.displayName() + ": ")
                    .withStyle(online ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
            if (online) {
                ChatFormatting countColor = s.onlinePlayers() > 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE;
                footer.append(Component.literal(s.onlinePlayers() + "/" + s.maxPlayers())
                        .withStyle(countColor));
                sig.append(s.serverId()).append(':').append(s.onlinePlayers()).append('/').append(s.maxPlayers());
            } else {
                footer.append(Component.literal("Offline").withStyle(ChatFormatting.RED));
                sig.append(s.serverId()).append(":OFF");
            }
        }

        String signature = sig.toString();
        if (!force && signature.equals(lastTabSignature)) {
            return;
        }
        lastTabSignature = signature;

        Component header = Component.literal("Linked Servers").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        ClientboundTabListPacket packet = new ClientboundTabListPacket(header, footer);
        for (ServerPlayer player : players) {
            if (excludingUuid != null && excludingUuid.equals(player.getUUID())) {
                continue;
            }
            if (player.connection != null) {
                player.connection.send(packet);
            }
        }
    }
}
