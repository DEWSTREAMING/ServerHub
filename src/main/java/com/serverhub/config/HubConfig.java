package com.serverhub.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class HubConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile HubConfig instance;

    public String role = "hub";
    public String serverId = "hub";
    public String serverDisplayName = "Hub";
    public String publicHost = "127.0.0.1";
    public int publicPort = 25565;
    public String hubHost = "127.0.0.1";
    public int hubPort = 25565;
    public boolean runRelayServer = true;
    public String relayHost = "127.0.0.1";
    public int relayPort = 25580;
    public String sharedSecret = "change-me-shared-secret";
    public boolean redirectToHubOnShutdown = true;
    public List<String> requiredModIds = new ArrayList<>();
    public List<String> extraServerConfigs = new ArrayList<>();
    public List<PortalDefinition> portals = new ArrayList<>();

    public static HubConfig get() {
        HubConfig current = instance;
        if (current == null) {
            return reload();
        }
        return current;
    }

    public static synchronized HubConfig reload() {
        Path path = configPath();
        HubConfig loaded = loadFrom(path);
        instance = loaded;
        return loaded;
    }

    public static synchronized void save() {
        saveTo(get(), configPath());
    }

    public static synchronized void setForTesting(HubConfig config) {
        if (config != null) {
            config.normalize();
        }
        instance = config;
    }

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("serverhub.json");
    }

    public static HubConfig loadFrom(Path path) {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                HubConfig cfg = GSON.fromJson(reader, HubConfig.class);
                if (cfg != null) {
                    cfg.normalize();
                    return cfg;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read {}, falling back to defaults", path, e);
            }
        }
        HubConfig defaults = new HubConfig();
        defaults.normalize();
        saveTo(defaults, path);
        return defaults;
    }

    public static void saveTo(HubConfig cfg, Path path) {
        try {
            cfg.normalize();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save {}", path, e);
        }
    }

    public boolean isHubRole() {
        return "hub".equalsIgnoreCase(role);
    }

    public void normalize() {
        if (role == null || role.isBlank()) {
            role = "hub";
        }
        if (serverId == null || serverId.isBlank()) {
            serverId = "hub";
        }
        if (serverDisplayName == null || serverDisplayName.isBlank()) {
            serverDisplayName = serverId;
        }
        if (publicHost == null || publicHost.isBlank()) {
            publicHost = "127.0.0.1";
        }
        if (hubHost == null || hubHost.isBlank()) {
            hubHost = "127.0.0.1";
        }
        if (relayHost == null || relayHost.isBlank()) {
            relayHost = "127.0.0.1";
        }
        if (sharedSecret == null) {
            sharedSecret = "";
        }
        if (requiredModIds == null) {
            requiredModIds = new ArrayList<>();
        }
        if (extraServerConfigs == null) {
            extraServerConfigs = new ArrayList<>();
        }
        if (portals == null) {
            portals = new ArrayList<>();
        }
    }

    public static final class PortalDefinition {
        public String name = "";
        public String targetServerId = "";
        public String dimension = "minecraft:overworld";
        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;

        public PortalDefinition() {}

        public PortalDefinition(
                String name,
                String targetServerId,
                String dimension,
                double minX,
                double minY,
                double minZ,
                double maxX,
                double maxY,
                double maxZ) {
            this.name = name;
            this.targetServerId = targetServerId;
            this.dimension = dimension;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public boolean contains(String dim, double x, double y, double z) {
            if (dimension == null || !dimension.equalsIgnoreCase(dim)) {
                return false;
            }
            double lowX = Math.min(minX, maxX);
            double highX = Math.max(minX, maxX);
            double lowY = Math.min(minY, maxY);
            double highY = Math.max(minY, maxY);
            double lowZ = Math.min(minZ, maxZ);
            double highZ = Math.max(minZ, maxZ);
            return x >= lowX && x <= highX
                    && y >= lowY && y <= highY
                    && z >= lowZ && z <= highZ;
        }
    }
}
