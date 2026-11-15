package com.serverhub.handshake;

import com.google.gson.Gson;
import com.serverhub.config.HubConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * Exports the running game server's synced datapack registries, tags, and mod IDs as a manifest
 * JSON string so it can be pushed to the Hub automatically over the relay.
 */
public final class ManifestExporter {
    private static final Gson GSON = new Gson();
    private static final Set<String> EXCLUDED_MODS = Set.of("minecraft", "neoforge", "forge", "javafml");
    private static final Set<String> EXCLUDED_REGISTRIES =
            Set.of("minecraft:dimension_type", "minecraft:test_environment", "minecraft:test_instance");

    private ManifestExporter() {}

    public static String exportJson(MinecraftServer server) {
        ManifestPayload payload = new ManifestPayload();
        String serverId = HubConfig.get().serverId;
        payload.packId = (serverId == null || serverId.isBlank()) ? "game" : serverId;
        payload.minecraftVersion = ModList.get()
                .getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("");

        TreeSet<String> mods = new TreeSet<>();
        for (var info : ModList.get().getMods()) {
            String id = info.getModId();
            if (id != null && !id.isBlank() && !EXCLUDED_MODS.contains(id)) {
                mods.add(id);
            }
        }
        payload.modIds.addAll(mods);

        RegistrySynchronization.networkedRegistries(server.registries()).forEach(entry -> {
            String regId = entry.key().location().toString();
            if (EXCLUDED_REGISTRIES.contains(regId)) {
                return;
            }
            List<String> keys = new ArrayList<>();
            entry.value().keySet().forEach(id -> keys.add(id.toString()));
            if (!keys.isEmpty()) {
                Collections.sort(keys);
                payload.registries.put(regId, keys);
            }
        });

        server.registryAccess().registries().forEach(entry -> {
            String regId = entry.key().location().toString();
            if (EXCLUDED_REGISTRIES.contains(regId)) {
                return;
            }
            List<String> tagNames = entry.value()
                    .getTagNames()
                    .map(tagKey -> tagKey.location().toString())
                    .sorted()
                    .toList();
            if (!tagNames.isEmpty()) {
                payload.tags.put(regId, tagNames);
            }
        });

        return GSON.toJson(payload);
    }

    private static final class ManifestPayload {
        String packId = "";
        String minecraftVersion = "";
        List<String> modIds = new ArrayList<>();
        Map<String, List<String>> registries = new LinkedHashMap<>();
        Map<String, List<String>> tags = new LinkedHashMap<>();
    }
}
