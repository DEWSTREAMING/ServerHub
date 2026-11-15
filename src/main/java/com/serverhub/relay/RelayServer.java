package com.serverhub.relay;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class RelayServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final int configuredPort;
    private final String sharedSecret;
    private final Map<String, ServerStatusInfo> statuses = new ConcurrentHashMap<>();
    private final Set<ClientSession> clients = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ScheduledExecutorService watchdog;
    private volatile Consumer<String> manifestListener;

    public RelayServer(int port, String sharedSecret) {
        this.configuredPort = port;
        this.sharedSecret = sharedSecret != null ? sharedSecret : "";
    }

    public void setManifestListener(Consumer<String> listener) {
        this.manifestListener = listener;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(configuredPort);
        running = true;

        acceptThread = new Thread(this::acceptLoop, "serverhub-relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serverhub-relay-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleAtFixedRate(this::expireStaleServers, 5, 5, TimeUnit.SECONDS);

        LOGGER.info("Server Hub relay listening on port {}", getPort());
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : configuredPort;
    }

    public Map<String, ServerStatusInfo> getStatuses() {
        return Map.copyOf(statuses);
    }

    public void stop() {
        running = false;
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        for (ClientSession session : List.copyOf(clients)) {
            session.close();
        }
        clients.clear();
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                ClientSession session = new ClientSession(socket);
                Thread handler = new Thread(session::run, "serverhub-relay-client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (running) {
                    LOGGER.warn("Relay accept error", e);
                }
            }
        }
    }

    private void expireStaleServers() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, ServerStatusInfo> entry : statuses.entrySet()) {
            ServerStatusInfo info = entry.getValue();
            if ("ONLINE".equalsIgnoreCase(info.status()) && (now - info.lastUpdatedMillis()) >= 15000L) {
                entry.setValue(info.copyWithStatus("OFFLINE", 0, now));
                changed = true;
            }
        }
        if (changed) {
            broadcastSnapshot();
        }
    }

    private void broadcastSnapshot() {
        List<ServerStatusInfo> snapshot = new ArrayList<>(statuses.values());
        String json = GSON.toJson(RelayMessage.statusSnapshot(snapshot));
        for (ClientSession session : clients) {
            if (session.authenticated) {
                session.sendJson(json);
            }
        }
    }

    private void broadcastToOthers(ClientSession sender, String json) {
        for (ClientSession session : clients) {
            if (session != sender && session.authenticated) {
                session.sendJson(json);
            }
        }
    }

    private final class ClientSession {
        private final Socket socket;
        private final Object writeLock = new Object();
        private BufferedWriter writer;
        private volatile boolean authenticated;
        private volatile String clientServerId;

        private ClientSession(Socket socket) {
            this.socket = socket;
        }

        private void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                socket.setSoTimeout(10000);
                String authLine = reader.readLine();
                if (authLine == null) {
                    return;
                }
                RelayMessage authMsg = GSON.fromJson(authLine, RelayMessage.class);
                if (authMsg == null
                        || !RelayMessage.TYPE_AUTH.equals(authMsg.type)
                        || !Objects.equals(sharedSecret, authMsg.secret)) {
                    LOGGER.warn("Rejected unauthenticated relay connection from {}", socket.getRemoteSocketAddress());
                    return;
                }
                socket.setSoTimeout(0);
                authenticated = true;
                clients.add(this);

                // Send current server list immediately upon authentication.
                sendJson(GSON.toJson(RelayMessage.statusSnapshot(new ArrayList<>(statuses.values()))));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    RelayMessage msg;
                    try {
                        msg = GSON.fromJson(line, RelayMessage.class);
                    } catch (Exception e) {
                        LOGGER.warn("Ignoring malformed relay payload from {}", socket.getRemoteSocketAddress());
                        continue;
                    }
                    if (msg == null || msg.type == null) {
                        continue;
                    }
                    handleMessage(msg, line);
                }
            } catch (IOException e) {
                if (running) {
                    LOGGER.debug("Relay client disconnected ({}): {}", clientServerId, e.getMessage());
                }
            } finally {
                clients.remove(this);
                markOfflineIfUnexpectedDisconnect();
                close();
            }
        }

        private void handleMessage(RelayMessage msg, String rawJson) {
            switch (msg.type) {
                case RelayMessage.TYPE_HEARTBEAT -> {
                    if (msg.serverStatus == null || msg.serverStatus.serverId().isBlank()) {
                        return;
                    }
                    clientServerId = msg.serverStatus.serverId();
                    ServerStatusInfo stamped = new ServerStatusInfo(
                            msg.serverStatus.serverId(),
                            msg.serverStatus.displayName(),
                            msg.serverStatus.host(),
                            msg.serverStatus.port(),
                            msg.serverStatus.status(),
                            msg.serverStatus.onlinePlayers(),
                            msg.serverStatus.maxPlayers(),
                            msg.serverStatus.requiredMods(),
                            System.currentTimeMillis());
                    statuses.put(stamped.serverId(), stamped);
                    broadcastSnapshot();
                }
                case RelayMessage.TYPE_CHAT, RelayMessage.TYPE_PLAYER_ORIGIN -> broadcastToOthers(this, rawJson);
                case RelayMessage.TYPE_REGISTRY_MANIFEST -> {
                    Consumer<String> listener = manifestListener;
                    if (listener != null && msg.manifestJson != null && !msg.manifestJson.isBlank()) {
                        listener.accept(msg.manifestJson);
                    }
                }
                default -> {}
            }
        }

        private void markOfflineIfUnexpectedDisconnect() {
            String id = clientServerId;
            if (id == null) {
                return;
            }
            ServerStatusInfo existing = statuses.get(id);
            if (existing != null && !"RESTARTING".equalsIgnoreCase(existing.status())) {
                statuses.put(id, existing.copyWithStatus("OFFLINE", 0, System.currentTimeMillis()));
                broadcastSnapshot();
            }
        }

        private void sendJson(String json) {
            if (writer == null || socket.isClosed()) {
                return;
            }
            synchronized (writeLock) {
                try {
                    writer.write(json);
                    writer.newLine();
                    writer.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        private void close() {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
