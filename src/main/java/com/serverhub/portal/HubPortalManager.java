package com.serverhub.portal;

import com.serverhub.config.HubConfig;
import com.serverhub.transfer.TransferManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class HubPortalManager {
    public static final long COOLDOWN_MS = 5000L;

    private static final Map<UUID, Long> PORTAL_COOLDOWNS = new ConcurrentHashMap<>();
    private static int tickCounter = 0;

    private HubPortalManager() {}

    public static void clearCooldownsForTesting() {
        PORTAL_COOLDOWNS.clear();
        tickCounter = 0;
    }

    public static boolean tryConsumeCooldown(UUID playerUuid, long nowMillis) {
        if (playerUuid == null) {
            return false;
        }
        Long last = PORTAL_COOLDOWNS.get(playerUuid);
        if (last != null && (nowMillis - last) < COOLDOWN_MS) {
            return false;
        }
        PORTAL_COOLDOWNS.put(playerUuid, nowMillis);
        return true;
    }

    public static void clearPlayer(UUID playerUuid) {
        if (playerUuid != null) {
            PORTAL_COOLDOWNS.remove(playerUuid);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        HubConfig cfg = HubConfig.get();
        if (!cfg.isHubRole()) {
            return;
        }
        if (++tickCounter % 10 != 0) {
            return;
        }
        List<HubConfig.PortalDefinition> portals = cfg.portals;
        if (portals == null || portals.isEmpty()) {
            return;
        }
        if (tickCounter % 40 == 0) {
            HubStructureGenerator.updatePortalSigns(server);
        }
        if (server.getPlayerList() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String dim = player.level().dimension().location().toString();
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();

            for (HubConfig.PortalDefinition portal : portals) {
                if (portal.contains(dim, x, y, z)) {
                    if (tryConsumeCooldown(player.getUUID(), now)) {
                        if (portal.targetServerId == null || portal.targetServerId.isBlank()) {
                            player.sendSystemMessage(Component.literal(
                                            "Portal '" + portal.name
                                                    + "' is not linked yet. Link it with /hubadmin linkportal "
                                                    + portal.name + " <serverId>.")
                                    .withStyle(ChatFormatting.YELLOW));
                        } else {
                            TransferManager.transferPlayerToServer(player, portal.targetServerId);
                        }
                    }
                    break;
                }
            }
        }
    }
}
