package com.serverhub.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class ChatMuteManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STORAGE_TYPE = new TypeToken<Map<String, PlayerMuteSettings>>() {}.getType();

    private static final Map<UUID, PlayerMuteSettings> SETTINGS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile Path storagePathOverride = null;

    private ChatMuteManager() {}

    public static synchronized void setStoragePathForTesting(Path path) {
        storagePathOverride = path;
        SETTINGS.clear();
        loaded = true;
    }

    public static synchronized void load() {
        SETTINGS.clear();
        Path path = storagePath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Map<String, PlayerMuteSettings> raw = GSON.fromJson(reader, STORAGE_TYPE);
                if (raw != null) {
                    for (Map.Entry<String, PlayerMuteSettings> entry : raw.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            PlayerMuteSettings s = entry.getValue();
                            if (s != null) {
                                s.normalize();
                                SETTINGS.put(uuid, s);
                            }
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load {}", path, e);
            }
        }
        loaded = true;
    }

    public static synchronized void save() {
        Path path = storagePath();
        Map<String, PlayerMuteSettings> out = new HashMap<>();
        for (Map.Entry<UUID, PlayerMuteSettings> entry : SETTINGS.entrySet()) {
            PlayerMuteSettings s = entry.getValue();
            if (s.localOnly || s.hideJoinLeave || !s.mutedServers.isEmpty()) {
                out.put(entry.getKey().toString(), s);
            }
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(out, STORAGE_TYPE, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save {}", path, e);
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static Path storagePath() {
        if (storagePathOverride != null) {
            return storagePathOverride;
        }
        return FMLPaths.CONFIGDIR.get().resolve("serverhub_mutes.json");
    }

    public static boolean shouldReceiveChat(
            UUID receiverUuid, String receiverServerId, String sourceServerId) {
        if (receiverServerId != null && receiverServerId.equalsIgnoreCase(sourceServerId)) {
            return true;
        }
        ensureLoaded();
        PlayerMuteSettings settings = SETTINGS.get(receiverUuid);
        if (settings == null) {
            return true;
        }
        if (settings.localOnly) {
            return false;
        }
        if (sourceServerId != null && settings.mutedServers.contains(sourceServerId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return true;
    }

    public static boolean shouldReceiveJoinLeave(
            UUID receiverUuid, String receiverServerId, String sourceServerId) {
        if (!shouldReceiveChat(receiverUuid, receiverServerId, sourceServerId)) {
            return false;
        }
        PlayerMuteSettings settings = SETTINGS.get(receiverUuid);
        return settings == null || !settings.hideJoinLeave;
    }

    public static void setHideJoinLeave(UUID playerUuid, boolean hideJoinLeave) {
        ensureLoaded();
        PlayerMuteSettings settings = SETTINGS.computeIfAbsent(playerUuid, ignored -> new PlayerMuteSettings());
        settings.hideJoinLeave = hideJoinLeave;
        save();
    }

    public static void setLocalOnly(UUID playerUuid, boolean localOnly) {
        ensureLoaded();
        PlayerMuteSettings settings = SETTINGS.computeIfAbsent(playerUuid, ignored -> new PlayerMuteSettings());
        settings.localOnly = localOnly;
        save();
    }

    public static boolean muteServer(UUID playerUuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        ensureLoaded();
        PlayerMuteSettings settings = SETTINGS.computeIfAbsent(playerUuid, ignored -> new PlayerMuteSettings());
        boolean added = settings.mutedServers.add(serverId.toLowerCase(Locale.ROOT));
        save();
        return added;
    }

    public static boolean unmuteServer(UUID playerUuid, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return false;
        }
        ensureLoaded();
        PlayerMuteSettings settings = SETTINGS.get(playerUuid);
        if (settings == null) {
            return false;
        }
        boolean removed = settings.mutedServers.remove(serverId.toLowerCase(Locale.ROOT));
        if (removed) {
            save();
        }
        return removed;
    }

    public static void clearMutes(UUID playerUuid) {
        ensureLoaded();
        PlayerMuteSettings settings = SETTINGS.get(playerUuid);
        if (settings != null) {
            settings.localOnly = false;
            settings.mutedServers.clear();
            if (!settings.hideJoinLeave) {
                SETTINGS.remove(playerUuid);
            }
            save();
        }
    }

    public static PlayerMuteSettings getSettings(UUID playerUuid) {
        ensureLoaded();
        PlayerMuteSettings existing = SETTINGS.get(playerUuid);
        if (existing == null) {
            return new PlayerMuteSettings();
        }
        return existing.copy();
    }

    public static final class PlayerMuteSettings {
        public boolean localOnly = false;
        public boolean hideJoinLeave = false;
        public Set<String> mutedServers = new HashSet<>();

        public PlayerMuteSettings() {}

        public void normalize() {
            if (mutedServers == null) {
                mutedServers = new HashSet<>();
                return;
            }
            Set<String> normalized = new HashSet<>();
            for (String server : mutedServers) {
                if (server != null && !server.isBlank()) {
                    normalized.add(server.toLowerCase(Locale.ROOT));
                }
            }
            mutedServers = normalized;
        }

        public PlayerMuteSettings copy() {
            PlayerMuteSettings copy = new PlayerMuteSettings();
            copy.localOnly = this.localOnly;
            copy.hideJoinLeave = this.hideJoinLeave;
            copy.mutedServers = new HashSet<>(this.mutedServers);
            return copy;
        }
    }
}
