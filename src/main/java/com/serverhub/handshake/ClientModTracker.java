package com.serverhub.handshake;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.ConnectionProtocol;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryComponent;

public final class ClientModTracker {
    private static final Map<UUID, Set<String>> PLAYER_CLIENT_MODS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PLAYER_ORIGIN_SERVER = new ConcurrentHashMap<>();

    private ClientModTracker() {}

    public static void recordClientChannels(
            UUID playerUuid, Map<ConnectionProtocol, Set<ModdedNetworkQueryComponent>> clientChannels) {
        if (playerUuid == null) {
            return;
        }
        Set<String> mods = new HashSet<>();
        mods.add("minecraft");
        mods.add("neoforge");
        if (clientChannels != null) {
            for (Set<ModdedNetworkQueryComponent> components : clientChannels.values()) {
                if (components == null) {
                    continue;
                }
                for (ModdedNetworkQueryComponent component : components) {
                    String namespace = component.id().getNamespace();
                    if (namespace != null && !namespace.isBlank()) {
                        mods.add(namespace.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        PLAYER_CLIENT_MODS.put(playerUuid, Set.copyOf(mods));
    }

    public static void recordClientModSet(UUID playerUuid, Collection<String> modIds) {
        if (playerUuid == null) {
            return;
        }
        Set<String> mods = new HashSet<>();
        mods.add("minecraft");
        mods.add("neoforge");
        if (modIds != null) {
            for (String modId : modIds) {
                if (modId != null && !modId.isBlank()) {
                    mods.add(modId.toLowerCase(Locale.ROOT));
                }
            }
        }
        PLAYER_CLIENT_MODS.put(playerUuid, Set.copyOf(mods));
    }

    public static Set<String> getClientMods(UUID playerUuid) {
        if (playerUuid == null) {
            return Set.of();
        }
        return PLAYER_CLIENT_MODS.getOrDefault(playerUuid, Set.of());
    }

    public static void setOriginServer(UUID playerUuid, String serverId) {
        if (playerUuid == null) {
            return;
        }
        if (serverId == null || serverId.isBlank()) {
            PLAYER_ORIGIN_SERVER.remove(playerUuid);
        } else {
            PLAYER_ORIGIN_SERVER.put(playerUuid, serverId);
        }
    }

    public static Optional<String> getOriginServer(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PLAYER_ORIGIN_SERVER.get(playerUuid));
    }

    public static List<String> getMissingMods(UUID playerUuid, Collection<String> requiredModIds) {
        return getMissingMods(playerUuid, null, requiredModIds);
    }

    public static List<String> getMissingMods(
            UUID playerUuid, String targetServerId, Collection<String> requiredModIds) {
        if (requiredModIds == null || requiredModIds.isEmpty()) {
            return List.of();
        }
        // A player evacuated straight from that backend is already running its modpack.
        if (targetServerId != null && !targetServerId.isBlank()) {
            Optional<String> origin = getOriginServer(playerUuid);
            if (origin.isPresent() && origin.get().equalsIgnoreCase(targetServerId)) {
                return List.of();
            }
        }
        Set<String> clientMods = getClientMods(playerUuid);
        List<String> missing = new ArrayList<>();
        for (String required : requiredModIds) {
            if (required == null || required.isBlank()) {
                continue;
            }
            if (!clientMods.contains(required.toLowerCase(Locale.ROOT))) {
                missing.add(required);
            }
        }
        return List.copyOf(missing);
    }

    public static void clearPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        PLAYER_CLIENT_MODS.remove(playerUuid);
        PLAYER_ORIGIN_SERVER.remove(playerUuid);
    }
}
