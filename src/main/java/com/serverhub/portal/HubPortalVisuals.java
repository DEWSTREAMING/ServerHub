package com.serverhub.portal;

import com.serverhub.ServerHubMod;
import com.serverhub.config.HubConfig;
import com.serverhub.mixin.DisplayAccessor;
import com.serverhub.mixin.TextDisplayAccessor;
import com.serverhub.relay.ServerStatusInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * Renders server-side portal visuals on the Hub without Nether portal nausea:
 * <ul>
 *   <li>Client-side {@code END_GATEWAY} starfield blocks inside online portal openings</li>
 *   <li>Status-aware swirling particles (bright end/portal swirls online, smoke/red dust offline)</li>
 *   <li>Floating {@link Display.TextDisplay} holograms showing server name, status, and player count</li>
 * </ul>
 */
public final class HubPortalVisuals {
    private static final String HOLOGRAM_TAG = "serverhub_portal_hologram";
    private static final String HOLOGRAM_PREFIX = "serverhub_portal:";
    private static final double PARTICLE_RANGE_SQ = 48.0D * 48.0D;
    private static final double BLOCK_SYNC_RANGE_SQ = 64.0D * 64.0D;

    private static final DustParticleOptions OFFLINE_DUST =
            new DustParticleOptions(new Vector3f(0.95F, 0.2F, 0.2F), 1.1F);
    private static final DustParticleOptions UNLINKED_DUST =
            new DustParticleOptions(new Vector3f(0.7F, 0.75F, 0.82F), 0.85F);

    private static final Map<UUID, Map<String, Boolean>> SENT_GATEWAY_STATES = new ConcurrentHashMap<>();
    private static final Map<String, UUID> HOLOGRAM_IDS = new ConcurrentHashMap<>();

    private HubPortalVisuals() {}

    public static void clearPlayer(UUID playerUuid) {
        if (playerUuid != null) {
            SENT_GATEWAY_STATES.remove(playerUuid);
        }
    }

    public static void clearAll() {
        SENT_GATEWAY_STATES.clear();
        HOLOGRAM_IDS.clear();
    }

    public static void spawnParticles(MinecraftServer server, int tickCounter) {
        List<HubConfig.PortalDefinition> portals = HubConfig.get().portals;
        if (server == null || portals == null || portals.isEmpty()) {
            return;
        }
        double angle = (tickCounter % 120) * (Math.PI * 2.0D / 120.0D);

        for (HubConfig.PortalDefinition portal : portals) {
            ServerLevel level = resolveLevel(server, portal.dimension);
            if (level == null || !hasNearbyPlayer(level, portal, PARTICLE_RANGE_SQ)) {
                continue;
            }
            PortalBounds bounds = PortalBounds.of(portal);
            if (!level.isLoaded(bounds.centerBlock())) {
                continue;
            }

            PortalVisualState state = resolveState(portal);
            if (state == PortalVisualState.ONLINE) {
                for (int i = 0; i < 3; i++) {
                    double a = angle + i * (Math.PI * 2.0D / 3.0D);
                    double px = bounds.centerX + Math.cos(a) * bounds.halfSpanX;
                    double py = bounds.centerY + Math.sin(a * 2.0D) * bounds.halfSpanY;
                    double pz = bounds.centerZ + Math.sin(a) * bounds.halfSpanZ;
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, px, py, pz, 1, 0.05D, 0.05D, 0.05D, 0.01D);
                }
                if (tickCounter % 8 == 0) {
                    double rx = bounds.centerX + (level.random.nextDouble() - 0.5D) * bounds.widthX;
                    double ry = bounds.centerY + (level.random.nextDouble() - 0.5D) * bounds.widthY;
                    double rz = bounds.centerZ + (level.random.nextDouble() - 0.5D) * bounds.widthZ;
                    level.sendParticles(ParticleTypes.END_ROD, rx, ry, rz, 1, 0.0D, 0.01D, 0.0D, 0.005D);
                }
            } else if (state == PortalVisualState.OFFLINE) {
                double rx = bounds.centerX + (level.random.nextDouble() - 0.5D) * bounds.widthX;
                double ry = bounds.centerY + (level.random.nextDouble() - 0.5D) * bounds.widthY;
                double rz = bounds.centerZ + (level.random.nextDouble() - 0.5D) * bounds.widthZ;
                level.sendParticles(ParticleTypes.SMOKE, rx, ry, rz, 1, 0.02D, 0.03D, 0.02D, 0.005D);
                level.sendParticles(OFFLINE_DUST, rx, ry, rz, 1, 0.1D, 0.1D, 0.1D, 0.0D);
            } else if (tickCounter % 8 == 0) {
                double rx = bounds.centerX + (level.random.nextDouble() - 0.5D) * bounds.widthX;
                double ry = bounds.centerY + (level.random.nextDouble() - 0.5D) * bounds.widthY;
                double rz = bounds.centerZ + (level.random.nextDouble() - 0.5D) * bounds.widthZ;
                level.sendParticles(UNLINKED_DUST, rx, ry, rz, 1, 0.05D, 0.05D, 0.05D, 0.0D);
            }
        }
    }

    public static void syncBlocksAndHolograms(MinecraftServer server) {
        List<HubConfig.PortalDefinition> portals = HubConfig.get().portals;
        if (server == null || portals == null || portals.isEmpty()) {
            return;
        }
        for (HubConfig.PortalDefinition portal : portals) {
            ServerLevel level = resolveLevel(server, portal.dimension);
            if (level == null) {
                continue;
            }
            PortalBounds bounds = PortalBounds.of(portal);
            if (!level.isLoaded(bounds.centerBlock())) {
                continue;
            }
            PortalVisualState state = resolveState(portal);
            syncPortalHologram(level, portal, bounds, state);
            syncGatewayBlocksForNearbyPlayers(level, portal, bounds, state == PortalVisualState.ONLINE);
        }
    }

    private static void syncGatewayBlocksForNearbyPlayers(
            ServerLevel level,
            HubConfig.PortalDefinition portal,
            PortalBounds bounds,
            boolean showGateway) {
        List<BlockPos> interiorAir = null;
        BlockState targetState = showGateway
                ? Blocks.END_GATEWAY.defaultBlockState()
                : Blocks.AIR.defaultBlockState();

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(bounds.centerX, bounds.centerY, bounds.centerZ) > BLOCK_SYNC_RANGE_SQ) {
                continue;
            }
            Map<String, Boolean> perPortal = SENT_GATEWAY_STATES.computeIfAbsent(
                    player.getUUID(), ignored -> new ConcurrentHashMap<>());
            Boolean previous = perPortal.get(portal.name);
            if (previous != null && previous.booleanValue() == showGateway) {
                continue;
            }
            if (interiorAir == null) {
                interiorAir = collectInteriorAirBlocks(level, bounds);
            }
            for (BlockPos pos : interiorAir) {
                player.connection.send(new ClientboundBlockUpdatePacket(pos, targetState));
            }
            perPortal.put(portal.name, showGateway);
        }
    }

    private static List<BlockPos> collectInteriorAirBlocks(ServerLevel level, PortalBounds bounds) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = bounds.minBlockX; x <= bounds.maxBlockX; x++) {
            for (int y = bounds.minBlockY; y <= bounds.maxBlockY; y++) {
                for (int z = bounds.minBlockZ; z <= bounds.maxBlockZ; z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).isAir()) {
                        positions.add(cursor.immutable());
                    }
                }
            }
        }
        return positions;
    }

    private static void syncPortalHologram(
            ServerLevel level,
            HubConfig.PortalDefinition portal,
            PortalBounds bounds,
            PortalVisualState state) {
        double holoX = bounds.centerX;
        double holoY = bounds.maxBlockY + 0.85D;
        double holoZ = bounds.centerZ;
        String portalTag = HOLOGRAM_PREFIX + portal.name;

        Display.TextDisplay display = findOrCleanupHologram(level, portal.name, portalTag, holoX, holoY, holoZ);
        if (display == null) {
            display = EntityType.TEXT_DISPLAY.create(level);
            if (display == null) {
                return;
            }
            display.setPos(holoX, holoY, holoZ);
            display.addTag(HOLOGRAM_TAG);
            display.addTag(portalTag);
            display.setNoGravity(true);
            display.setInvulnerable(true);
            ((DisplayAccessor) display)
                    .serverhub$setBillboardConstraints(Display.BillboardConstraints.CENTER);
            ((TextDisplayAccessor) display).serverhub$setBackgroundColor(0x88000000);
            level.addFreshEntity(display);
        } else {
            display.setPos(holoX, holoY, holoZ);
        }

        HOLOGRAM_IDS.put(portal.name, display.getUUID());
        ((TextDisplayAccessor) display).serverhub$setText(buildHologramText(portal, state));
    }

    private static Display.TextDisplay findOrCleanupHologram(
            ServerLevel level, String portalName, String portalTag, double x, double y, double z) {
        UUID cachedId = HOLOGRAM_IDS.get(portalName);
        if (cachedId != null) {
            Entity existing = level.getEntity(cachedId);
            if (existing instanceof Display.TextDisplay td && existing.isAlive()) {
                return td;
            }
            HOLOGRAM_IDS.remove(portalName);
        }

        AABB searchBox = new AABB(x - 4.0D, y - 4.0D, z - 4.0D, x + 4.0D, y + 4.0D, z + 4.0D);
        List<Display.TextDisplay> matches = level.getEntitiesOfClass(
                Display.TextDisplay.class,
                searchBox,
                e -> e.getTags().contains(HOLOGRAM_TAG) && e.getTags().contains(portalTag));
        Display.TextDisplay kept = null;
        for (Display.TextDisplay match : matches) {
            if (kept == null) {
                kept = match;
            } else {
                match.discard();
            }
        }
        return kept;
    }

    private static Component buildHologramText(
            HubConfig.PortalDefinition portal, PortalVisualState state) {
        if (state == PortalVisualState.UNLINKED) {
            return Component.literal("[" + portal.name + "]\n")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                    .append(Component.literal("Unlinked Portal").withStyle(ChatFormatting.GRAY));
        }

        ServerStatusInfo info = ServerHubMod.getServerStatus(portal.targetServerId);
        String name = (info != null && info.displayName() != null && !info.displayName().isBlank())
                ? info.displayName()
                : portal.targetServerId;

        if (info != null && info.isOnline()) {
            return Component.literal(name + "\n")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                    .append(Component.literal("ONLINE ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(info.onlinePlayers() + "/" + info.maxPlayers())
                            .withStyle(ChatFormatting.WHITE));
        }

        String statusLabel = (info != null && "RESTARTING".equalsIgnoreCase(info.status()))
                ? "RESTARTING"
                : "OFFLINE";
        ChatFormatting statusColor = "RESTARTING".equals(statusLabel)
                ? ChatFormatting.YELLOW
                : ChatFormatting.RED;

        return Component.literal(name + "\n")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD)
                .append(Component.literal(statusLabel).withStyle(statusColor));
    }

    private static PortalVisualState resolveState(HubConfig.PortalDefinition portal) {
        if (portal.targetServerId == null || portal.targetServerId.isBlank()) {
            return PortalVisualState.UNLINKED;
        }
        ServerStatusInfo info = ServerHubMod.getServerStatus(portal.targetServerId);
        return (info != null && info.isOnline()) ? PortalVisualState.ONLINE : PortalVisualState.OFFLINE;
    }

    private static boolean hasNearbyPlayer(
            ServerLevel level, HubConfig.PortalDefinition portal, double rangeSq) {
        PortalBounds bounds = PortalBounds.of(portal);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(bounds.centerX, bounds.centerY, bounds.centerZ) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionId) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(
                dimensionId != null && !dimensionId.isBlank() ? dimensionId : "minecraft:overworld");
        if (dimLoc == null) {
            return server.overworld();
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimLoc);
        ServerLevel level = server.getLevel(key);
        return level != null ? level : server.overworld();
    }

    private enum PortalVisualState {
        UNLINKED,
        ONLINE,
        OFFLINE
    }

    private static final class PortalBounds {
        final int minBlockX;
        final int minBlockY;
        final int minBlockZ;
        final int maxBlockX;
        final int maxBlockY;
        final int maxBlockZ;
        final double centerX;
        final double centerY;
        final double centerZ;
        final double widthX;
        final double widthY;
        final double widthZ;
        final double halfSpanX;
        final double halfSpanY;
        final double halfSpanZ;

        private PortalBounds(
                int minBlockX,
                int minBlockY,
                int minBlockZ,
                int maxBlockX,
                int maxBlockY,
                int maxBlockZ) {
            this.minBlockX = minBlockX;
            this.minBlockY = minBlockY;
            this.minBlockZ = minBlockZ;
            this.maxBlockX = maxBlockX;
            this.maxBlockY = maxBlockY;
            this.maxBlockZ = maxBlockZ;
            this.centerX = (minBlockX + maxBlockX + 1) * 0.5D;
            this.centerY = (minBlockY + maxBlockY + 1) * 0.5D;
            this.centerZ = (minBlockZ + maxBlockZ + 1) * 0.5D;
            this.widthX = Math.max(0.6D, (maxBlockX - minBlockX + 1) * 0.8D);
            this.widthY = Math.max(0.6D, (maxBlockY - minBlockY + 1) * 0.8D);
            this.widthZ = Math.max(0.6D, (maxBlockZ - minBlockZ + 1) * 0.8D);
            this.halfSpanX = Math.max(0.35D, (maxBlockX - minBlockX + 1) * 0.45D);
            this.halfSpanY = Math.max(0.35D, (maxBlockY - minBlockY + 1) * 0.45D);
            this.halfSpanZ = Math.max(0.35D, (maxBlockZ - minBlockZ + 1) * 0.45D);
        }

        static PortalBounds of(HubConfig.PortalDefinition portal) {
            int minX = (int) Math.floor(Math.min(portal.minX, portal.maxX));
            int minY = (int) Math.floor(Math.min(portal.minY, portal.maxY));
            int minZ = (int) Math.floor(Math.min(portal.minZ, portal.maxZ));
            int maxX = Math.max(minX, (int) Math.ceil(Math.max(portal.minX, portal.maxX)) - 1);
            int maxY = Math.max(minY, (int) Math.ceil(Math.max(portal.minY, portal.maxY)) - 1);
            int maxZ = Math.max(minZ, (int) Math.ceil(Math.max(portal.minZ, portal.maxZ)) - 1);
            return new PortalBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        BlockPos centerBlock() {
            return BlockPos.containing(centerX, centerY, centerZ);
        }
    }
}
