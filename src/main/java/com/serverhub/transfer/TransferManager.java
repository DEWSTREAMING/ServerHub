package com.serverhub.transfer;

import com.mojang.logging.LogUtils;
import com.serverhub.ServerHubMod;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ClientModTracker;
import com.serverhub.relay.RelayClient;
import com.serverhub.relay.ServerStatusInfo;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

public final class TransferManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean EVACUATED = new AtomicBoolean(false);

    private TransferManager() {}

    public static void resetShutdownState() {
        EVACUATED.set(false);
    }

    public static boolean transferPlayerToServer(ServerPlayer player, String targetServerId) {
        HubConfig cfg = HubConfig.get();
        if (targetServerId == null || targetServerId.isBlank()) {
            player.sendSystemMessage(Component.literal("Specify a target server ID.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (targetServerId.equalsIgnoreCase(cfg.serverId)) {
            String msg = cfg.isHubRole()
                    ? "You are already on the Hub."
                    : "You are already on " + cfg.serverDisplayName + ".";
            player.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.YELLOW));
            return false;
        }

        ServerStatusInfo target = ServerHubMod.getServerStatus(targetServerId);
        if (target == null || !target.isOnline()) {
            String status = target == null ? "OFFLINE" : target.status();
            player.sendSystemMessage(Component.literal(
                            "Server " + targetServerId + " is currently " + status + ". Please wait in the hub.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        List<String> missing = ClientModTracker.getMissingMods(
                player.getUUID(), target.serverId(), target.requiredMods());
        if (!missing.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                            "Your loaded modpack does not match "
                                    + target.displayName()
                                    + " (missing: "
                                    + String.join(", ", missing)
                                    + ").")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        RelayClient relay = ServerHubMod.getRelayClient();
        if (!cfg.isHubRole()) {
            ClientModTracker.setOriginServer(player.getUUID(), cfg.serverId);
            if (relay != null) {
                relay.sendPlayerOrigin(player.getUUID(), cfg.serverId);
            }
        }

        sendTransferWithAuthCookie(player, target.host(), target.port());
        return true;
    }

    public static boolean transferPlayerToHub(ServerPlayer player) {
        HubConfig cfg = HubConfig.get();
        if (cfg.isHubRole()) {
            player.sendSystemMessage(Component.literal("You are already on the Hub.").withStyle(ChatFormatting.YELLOW));
            return false;
        }

        ClientModTracker.setOriginServer(player.getUUID(), cfg.serverId);
        RelayClient relay = ServerHubMod.getRelayClient();
        if (relay != null) {
            relay.sendPlayerOrigin(player.getUUID(), cfg.serverId);
        }

        sendTransferWithAuthCookie(player, cfg.hubHost, cfg.hubPort);
        return true;
    }

    public static void evacuatePlayersToHubOnShutdown(MinecraftServer server) {
        HubConfig cfg = HubConfig.get();
        if (cfg.isHubRole() || !cfg.redirectToHubOnShutdown) {
            return;
        }
        if (!EVACUATED.compareAndSet(false, true)) {
            return;
        }

        RelayClient relay = ServerHubMod.getRelayClient();
        if (relay != null) {
            relay.sendHeartbeat(ServerHubMod.buildLocalStatus(server, "RESTARTING"));
        }

        if (server.getPlayerList() == null) {
            return;
        }

        server.getPlayerList().saveAll();
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            return;
        }

        LOGGER.info("Redirecting {} player(s) to Hub ({}:{}) before shutdown", players.size(), cfg.hubHost, cfg.hubPort);
        for (ServerPlayer player : players) {
            if (relay != null) {
                relay.sendPlayerOrigin(player.getUUID(), cfg.serverId);
            }
            player.sendSystemMessage(Component.literal("Server is restarting. Redirecting you to the Hub...")
                    .withStyle(ChatFormatting.YELLOW));
            sendTransferWithAuthCookie(player, cfg.hubHost, cfg.hubPort);
            player.connection.getConnection().flushChannel();
        }

        try {
            Thread.sleep(350L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendTransferWithAuthCookie(ServerPlayer player, String host, int port) {
        byte[] token = TransferAuthToken.issue(player.getGameProfile());
        if (token != null) {
            player.connection.send(new ClientboundStoreCookiePacket(TransferAuthToken.COOKIE_KEY, token));
        }
        player.connection.send(new ClientboundTransferPacket(host, port));
    }
}
