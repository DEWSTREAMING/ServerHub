package com.serverhub.relay;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.serverhub.handshake.ClientModTracker;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class RelayClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final String host;
    private final int port;
    private final String secret;
    private final Map<String, ServerStatusInfo> knownServers = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile boolean running;
    private volatile boolean connected;
    private Socket socket;
    private BufferedWriter writer;
    private Thread ioThread;
    private ScheduledExecutorService heartbeatExecutor;

    private volatile Supplier<ServerStatusInfo> heartbeatSupplier;
    private volatile BiConsumer<ServerStatusInfo, String> statusChangeListener;
    private volatile Consumer<RelayMessage> chatListener;
    private volatile BiConsumer<UUID, String> playerOriginListener;

    public RelayClient(String host, int port, String secret) {
        this.host = host != null ? host : "127.0.0.1";
        this.port = port;
        this.secret = secret != null ? secret : "";
    }

    public void setHeartbeatSupplier(Supplier<ServerStatusInfo> supplier) {
        this.heartbeatSupplier = supplier;
    }

    public void setStatusChangeListener(BiConsumer<ServerStatusInfo, String> listener) {
        this.statusChangeListener = listener;
    }

    public void setChatListener(Consumer<RelayMessage> listener) {
        this.chatListener = listener;
    }

    public void setPlayerOriginListener(BiConsumer<UUID, String> listener) {
        this.playerOriginListener = listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;

        ioThread = new Thread(this::connectionLoop, "serverhub-relay-client");
        ioThread.setDaemon(true);
        ioThread.start();

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serverhub-relay-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::sendScheduledHeartbeat, 1, 3, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        connected = false;
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        closeSocket();
    }

    public boolean isConnected() {
        return connected;
    }

    public Map<String, ServerStatusInfo> getKnownServers() {
        return Map.copyOf(knownServers);
    }

    public ServerStatusInfo getServerStatus(String serverId) {
        if (serverId == null) {
            return null;
        }
        ServerStatusInfo exact = knownServers.get(serverId);
        if (exact != null) {
            return exact;
        }
        for (ServerStatusInfo info : knownServers.values()) {
            if (info.serverId().equalsIgnoreCase(serverId)) {
                return info;
            }
        }
        return null;
    }

    public boolean sendHeartbeat(ServerStatusInfo status) {
        if (status == null) {
            return false;
        }
        return sendMessage(RelayMessage.heartbeat(status));
    }

    public boolean sendChat(
            String sourceServerId,
            String sourceDisplayName,
            UUID senderUuid,
            String senderName,
            String chatMessage) {
        return sendMessage(RelayMessage.chat(
                sourceServerId,
                sourceDisplayName,
                senderUuid != null ? senderUuid.toString() : "",
                senderName,
                chatMessage));
    }

    public boolean sendPlayerOrigin(UUID playerUuid, String originServerId) {
        if (playerUuid == null || originServerId == null || originServerId.isBlank()) {
            return false;
        }
        return sendMessage(RelayMessage.playerOrigin(playerUuid.toString(), originServerId));
    }

    private void sendScheduledHeartbeat() {
        if (!running || !connected) {
            return;
        }
        Supplier<ServerStatusInfo> supplier = this.heartbeatSupplier;
        if (supplier != null) {
            ServerStatusInfo status = supplier.get();
            if (status != null) {
                sendHeartbeat(status);
            }
        }
    }

    private void connectionLoop() {
        while (running) {
            try (Socket s = new Socket(host, port)) {
                s.setTcpNoDelay(true);
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

                synchronized (writeLock) {
                    this.socket = s;
                    this.writer = out;
                }
                sendMessage(RelayMessage.auth(secret));
                connected = true;

                // Push immediate status upon connecting instead of waiting for the next tick interval.
                sendScheduledHeartbeat();

                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        RelayMessage msg = GSON.fromJson(line, RelayMessage.class);
                        if (msg != null && msg.type != null) {
                            handleIncoming(msg);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to parse incoming relay message", e);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    LOGGER.debug("Relay connection to {}:{} lost: {}", host, port, e.getMessage());
                }
            } finally {
                connected = false;
                closeSocket();
            }

            if (running) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleIncoming(RelayMessage msg) {
        switch (msg.type) {
            case RelayMessage.TYPE_STATUS_SNAPSHOT -> {
                if (msg.allServers == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (ServerStatusInfo incoming : msg.allServers) {
                    if (incoming == null || incoming.serverId().isBlank()) {
                        continue;
                    }
                    // Refresh timestamp locally for active servers to tolerate clock skew between hosts.
                    long effectiveTimestamp = "ONLINE".equalsIgnoreCase(incoming.status())
                            ? Math.max(incoming.lastUpdatedMillis(), now)
                            : incoming.lastUpdatedMillis();
                    ServerStatusInfo normalized = new ServerStatusInfo(
                            incoming.serverId(),
                            incoming.displayName(),
                            incoming.host(),
                            incoming.port(),
                            incoming.status(),
                            incoming.onlinePlayers(),
                            incoming.maxPlayers(),
                            incoming.requiredMods(),
                            effectiveTimestamp);

                    ServerStatusInfo previous = knownServers.put(normalized.serverId(), normalized);
                    String previousStatus = previous != null ? previous.status() : null;
                    if (previousStatus != null && !previousStatus.equalsIgnoreCase(normalized.status())) {
                        BiConsumer<ServerStatusInfo, String> listener = this.statusChangeListener;
                        if (listener != null) {
                            listener.accept(normalized, previousStatus);
                        }
                    }
                }
            }
            case RelayMessage.TYPE_CHAT -> {
                Consumer<RelayMessage> listener = this.chatListener;
                if (listener != null) {
                    listener.accept(msg);
                }
            }
            case RelayMessage.TYPE_PLAYER_ORIGIN -> {
                if (msg.playerUuid == null || msg.originServerId == null) {
                    return;
                }
                try {
                    UUID uuid = UUID.fromString(msg.playerUuid);
                    ClientModTracker.setOriginServer(uuid, msg.originServerId);
                    BiConsumer<UUID, String> listener = this.playerOriginListener;
                    if (listener != null) {
                        listener.accept(uuid, msg.originServerId);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            default -> {}
        }
    }

    private boolean sendMessage(RelayMessage msg) {
        synchronized (writeLock) {
            if (writer == null || socket == null || socket.isClosed()) {
                return false;
            }
            try {
                writer.write(GSON.toJson(msg));
                writer.newLine();
                writer.flush();
                return true;
            } catch (IOException e) {
                connected = false;
                closeSocket();
                return false;
            }
        }
    }

    private void closeSocket() {
        synchronized (writeLock) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                socket = null;
            }
            writer = null;
        }
    }
}
