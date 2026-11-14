package com.serverhub.relay;

import java.util.ArrayList;
import java.util.List;

public final class ServerStatusInfo {
    public String serverId = "";
    public String displayName = "";
    public String host = "127.0.0.1";
    public int port = 25565;
    public String status = "OFFLINE";
    public int onlinePlayers = 0;
    public int maxPlayers = 20;
    public List<String> requiredMods = new ArrayList<>();
    public long lastUpdatedMillis = 0L;

    public ServerStatusInfo() {}

    public ServerStatusInfo(
            String serverId,
            String displayName,
            String host,
            int port,
            String status,
            int onlinePlayers,
            int maxPlayers,
            List<String> requiredMods,
            long lastUpdatedMillis) {
        this.serverId = serverId != null ? serverId : "";
        this.displayName = displayName != null ? displayName : this.serverId;
        this.host = host != null ? host : "127.0.0.1";
        this.port = port;
        this.status = status != null ? status : "OFFLINE";
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.requiredMods = requiredMods != null ? new ArrayList<>(requiredMods) : new ArrayList<>();
        this.lastUpdatedMillis = lastUpdatedMillis;
    }

    public String serverId() {
        return serverId != null ? serverId : "";
    }

    public String displayName() {
        return (displayName != null && !displayName.isBlank()) ? displayName : serverId();
    }

    public String host() {
        return host != null ? host : "127.0.0.1";
    }

    public int port() {
        return port;
    }

    public String status() {
        return status != null ? status : "OFFLINE";
    }

    public int onlinePlayers() {
        return onlinePlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public List<String> requiredMods() {
        return requiredMods != null ? requiredMods : List.of();
    }

    public long lastUpdatedMillis() {
        return lastUpdatedMillis;
    }

    public boolean isOnline() {
        return "ONLINE".equalsIgnoreCase(status)
                && (System.currentTimeMillis() - lastUpdatedMillis) < 15000L;
    }

    public ServerStatusInfo copyWithStatus(String newStatus, int players, long timestampMillis) {
        return new ServerStatusInfo(
                serverId(),
                displayName(),
                host(),
                port(),
                newStatus,
                players,
                maxPlayers(),
                requiredMods(),
                timestampMillis);
    }
}
