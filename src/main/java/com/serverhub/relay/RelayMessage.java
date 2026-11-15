package com.serverhub.relay;

import java.util.List;

public final class RelayMessage {
    public static final String TYPE_AUTH = "AUTH";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_STATUS_SNAPSHOT = "STATUS_SNAPSHOT";
    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_PLAYER_ORIGIN = "PLAYER_ORIGIN";
    public static final String TYPE_REGISTRY_MANIFEST = "REGISTRY_MANIFEST";
    public static final String TYPE_PLAYER_PRESENCE = "PLAYER_PRESENCE";

    public String type;
    public String secret;
    public ServerStatusInfo serverStatus;
    public List<ServerStatusInfo> allServers;
    public String sourceServerId;
    public String sourceDisplayName;
    public String senderUuid;
    public String senderName;
    public String chatMessage;
    public String playerUuid;
    public String originServerId;
    public String manifestJson;
    public boolean joined;

    public RelayMessage() {}

    public static RelayMessage auth(String secret) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_AUTH;
        msg.secret = secret;
        return msg;
    }

    public static RelayMessage heartbeat(ServerStatusInfo status) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_HEARTBEAT;
        msg.serverStatus = status;
        return msg;
    }

    public static RelayMessage statusSnapshot(List<ServerStatusInfo> servers) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_STATUS_SNAPSHOT;
        msg.allServers = servers;
        return msg;
    }

    public static RelayMessage chat(
            String sourceServerId,
            String sourceDisplayName,
            String senderUuid,
            String senderName,
            String chatMessage) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_CHAT;
        msg.sourceServerId = sourceServerId;
        msg.sourceDisplayName = sourceDisplayName;
        msg.senderUuid = senderUuid;
        msg.senderName = senderName;
        msg.chatMessage = chatMessage;
        return msg;
    }

    public static RelayMessage playerOrigin(String playerUuid, String originServerId) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_PLAYER_ORIGIN;
        msg.playerUuid = playerUuid;
        msg.originServerId = originServerId;
        return msg;
    }

    public static RelayMessage registryManifest(String manifestJson) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_REGISTRY_MANIFEST;
        msg.manifestJson = manifestJson;
        return msg;
    }

    public static RelayMessage playerPresence(
            String sourceServerId,
            String sourceDisplayName,
            String playerUuid,
            String playerName,
            boolean joined) {
        RelayMessage msg = new RelayMessage();
        msg.type = TYPE_PLAYER_PRESENCE;
        msg.sourceServerId = sourceServerId;
        msg.sourceDisplayName = sourceDisplayName;
        msg.playerUuid = playerUuid;
        msg.senderName = playerName;
        msg.joined = joined;
        return msg;
    }
}
