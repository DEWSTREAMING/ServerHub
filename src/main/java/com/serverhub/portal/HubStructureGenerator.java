package com.serverhub.portal;

import com.serverhub.ServerHubMod;
import com.serverhub.config.HubConfig;
import com.serverhub.relay.ServerStatusInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

public final class HubStructureGenerator {
    public static final List<String> PREMADE_PORTAL_NAMES = List.of("north", "east", "south", "west");

    private static final int HUB_HALF_SIZE = 14;
    private static final int CLEAR_HEIGHT = 9;
    private static final int PORTAL_DISTANCE = 12;

    private HubStructureGenerator() {}

    public static List<HubConfig.PortalDefinition> generateHub(ServerLevel level, BlockPos floorCenter) {
        int cx = floorCenter.getX();
        int cy = floorCenter.getY();
        int cz = floorCenter.getZ();
        String dim = level.dimension().identifier().toString();

        clearVolumeAndBuildFloor(level, cx, cy, cz);

        List<PortalSpec> specs = List.of(
                new PortalSpec("north", cx, cz - PORTAL_DISTANCE, true, Direction.SOUTH,
                        cx - 1, cy + 1, cz - PORTAL_DISTANCE, cx + 2, cy + 4, cz - PORTAL_DISTANCE + 1),
                new PortalSpec("south", cx, cz + PORTAL_DISTANCE, true, Direction.NORTH,
                        cx - 1, cy + 1, cz + PORTAL_DISTANCE, cx + 2, cy + 4, cz + PORTAL_DISTANCE + 1),
                new PortalSpec("east", cx + PORTAL_DISTANCE, cz, false, Direction.WEST,
                        cx + PORTAL_DISTANCE, cy + 1, cz - 1, cx + PORTAL_DISTANCE + 1, cy + 4, cz + 2),
                new PortalSpec("west", cx - PORTAL_DISTANCE, cz, false, Direction.EAST,
                        cx - PORTAL_DISTANCE, cy + 1, cz - 1, cx - PORTAL_DISTANCE + 1, cy + 4, cz + 2));

        List<HubConfig.PortalDefinition> generated = new ArrayList<>(specs.size());
        HubConfig cfg = HubConfig.get();

        for (PortalSpec spec : specs) {
            buildPortalFrame(level, cy, spec);
            String existingTarget = findExistingTarget(cfg.portals, spec.name);
            cfg.portals.removeIf(p -> spec.name.equalsIgnoreCase(p.name));
            HubConfig.PortalDefinition portal = new HubConfig.PortalDefinition(
                    spec.name,
                    existingTarget,
                    dim,
                    spec.minX,
                    spec.minY,
                    spec.minZ,
                    spec.maxX,
                    spec.maxY,
                    spec.maxZ);
            cfg.portals.add(portal);
            generated.add(portal);
        }

        HubConfig.save();
        level.setRespawnData(LevelData.RespawnData.of(level.dimension(), new BlockPos(cx, cy + 1, cz), 0.0f, 0.0f));
        updatePortalSigns(level.getServer());
        return generated;
    }

    public static boolean linkPortal(MinecraftServer server, String portalName, String targetServerId) {
        if (portalName == null || portalName.isBlank()) {
            return false;
        }
        HubConfig cfg = HubConfig.get();
        for (HubConfig.PortalDefinition portal : cfg.portals) {
            if (portalName.equalsIgnoreCase(portal.name)) {
                portal.targetServerId = isUnlinkKeyword(targetServerId) ? "" : targetServerId;
                HubConfig.save();
                updatePortalSign(server, portal);
                return true;
            }
        }
        return false;
    }

    public static void updatePortalSigns(MinecraftServer server) {
        if (server == null) {
            return;
        }
        HubConfig cfg = HubConfig.get();
        if (cfg.portals == null || cfg.portals.isEmpty()) {
            return;
        }
        for (HubConfig.PortalDefinition portal : cfg.portals) {
            updatePortalSign(server, portal);
        }
    }

    public static void updatePortalSign(MinecraftServer server, HubConfig.PortalDefinition portal) {
        if (server == null || portal == null) {
            return;
        }
        ServerLevel level = resolveLevel(server, portal.dimension);
        if (level == null) {
            return;
        }

        int scanMinX = (int) Math.floor(Math.min(portal.minX, portal.maxX)) - 2;
        int scanMaxX = (int) Math.ceil(Math.max(portal.minX, portal.maxX)) + 2;
        int scanMinY = (int) Math.floor(Math.min(portal.minY, portal.maxY)) - 1;
        int scanMaxY = (int) Math.ceil(Math.max(portal.minY, portal.maxY)) + 2;
        int scanMinZ = (int) Math.floor(Math.min(portal.minZ, portal.maxZ)) - 2;
        int scanMaxZ = (int) Math.ceil(Math.max(portal.minZ, portal.maxZ)) + 2;

        Component[] lines = buildSignLines(portal);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = scanMinX; x <= scanMaxX; x++) {
            for (int y = scanMinY; y <= scanMaxY; y++) {
                for (int z = scanMinZ; z <= scanMaxZ; z++) {
                    pos.set(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof SignBlockEntity sign) {
                        applySignText(level, pos, sign, lines);
                    }
                }
            }
        }
    }

    private static void clearVolumeAndBuildFloor(ServerLevel level, int cx, int cy, int cz) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -HUB_HALF_SIZE; dx <= HUB_HALF_SIZE; dx++) {
            for (int dz = -HUB_HALF_SIZE; dz <= HUB_HALF_SIZE; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                for (int dy = 1; dy <= CLEAR_HEIGHT; dy++) {
                    pos.set(x, cy + dy, z);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }

                pos.set(x, cy - 1, z);
                level.setBlock(pos, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);

                boolean isBorder = Math.abs(dx) == HUB_HALF_SIZE || Math.abs(dz) == HUB_HALF_SIZE;
                boolean isCenterPad = Math.abs(dx) <= 2 && Math.abs(dz) <= 2;
                boolean isWalkway = (Math.abs(dx) <= 1 && Math.abs(dz) <= PORTAL_DISTANCE)
                        || (Math.abs(dz) <= 1 && Math.abs(dx) <= PORTAL_DISTANCE);

                BlockState floorState;
                if (isBorder) {
                    floorState = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                } else if (isCenterPad) {
                    floorState = (dx == 0 && dz == 0)
                            ? Blocks.SEA_LANTERN.defaultBlockState()
                            : Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState();
                } else if (isWalkway) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dz));
                    floorState = (dist == 6 || dist == 10)
                            ? Blocks.SEA_LANTERN.defaultBlockState()
                            : Blocks.QUARTZ_BRICKS.defaultBlockState();
                } else if (Math.abs(dx) == 7 && Math.abs(dz) == 7) {
                    floorState = Blocks.SEA_LANTERN.defaultBlockState();
                } else {
                    floorState = (((dx + dz) & 1) == 0)
                            ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.POLISHED_ANDESITE.defaultBlockState();
                }

                pos.set(x, cy, z);
                level.setBlock(pos, floorState, Block.UPDATE_ALL);

                if (isBorder) {
                    BlockState wallTop = Math.floorMod(dx + dz, 4) == 0
                            ? Blocks.SEA_LANTERN.defaultBlockState()
                            : Blocks.STONE_BRICK_WALL.defaultBlockState();
                    pos.set(x, cy + 1, z);
                    level.setBlock(pos, wallTop, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void buildPortalFrame(ServerLevel level, int cy, PortalSpec spec) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Build solid pillars, threshold, and top beam before attaching the wall sign.
        for (int offset = -2; offset <= 2; offset++) {
            int x = spec.widthAlongX ? spec.centerX + offset : spec.centerX;
            int z = spec.widthAlongX ? spec.centerZ : spec.centerZ + offset;
            boolean isPillar = Math.abs(offset) == 2;

            if (!isPillar) {
                pos.set(x, cy - 1, z);
                level.setBlock(pos, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
                pos.set(x, cy, z);
                level.setBlock(pos, Blocks.CYAN_STAINED_GLASS.defaultBlockState(), Block.UPDATE_ALL);
            }

            for (int dy = 1; dy <= 3; dy++) {
                pos.set(x, cy + dy, z);
                if (isPillar) {
                    BlockState pillarBlock = (dy == 1)
                            ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                            : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                    level.setBlock(pos, pillarBlock, Block.UPDATE_ALL);
                } else {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }

            pos.set(x, cy + 4, z);
            BlockState topBlock = isPillar
                    ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                    : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            level.setBlock(pos, topBlock, Block.UPDATE_ALL);
        }

        BlockPos signPos = new BlockPos(
                spec.centerX + spec.inwardFacing.getStepX(),
                cy + 4,
                spec.centerZ + spec.inwardFacing.getStepZ());
        BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, spec.inwardFacing);
        level.setBlock(signPos, signState, Block.UPDATE_ALL);
    }

    private static String findExistingTarget(List<HubConfig.PortalDefinition> portals, String name) {
        if (portals == null) {
            return "";
        }
        for (HubConfig.PortalDefinition portal : portals) {
            if (name.equalsIgnoreCase(portal.name)
                    && portal.targetServerId != null
                    && !portal.targetServerId.isBlank()) {
                return portal.targetServerId;
            }
        }
        return "";
    }

    private static boolean isUnlinkKeyword(String targetServerId) {
        return targetServerId == null
                || targetServerId.isBlank()
                || "unlinked".equalsIgnoreCase(targetServerId)
                || "none".equalsIgnoreCase(targetServerId);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return server.overworld();
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (dimensionId.equalsIgnoreCase(level.dimension().identifier().toString())) {
                return level;
            }
        }
        return null;
    }

    private static Component[] buildSignLines(HubConfig.PortalDefinition portal) {
        Component line0 = Component.literal("[" + portal.name + "]").withStyle(ChatFormatting.AQUA);
        if (portal.targetServerId == null || portal.targetServerId.isBlank()) {
            return new Component[] {
                line0,
                Component.literal("UNLINKED").withStyle(ChatFormatting.YELLOW),
                Component.literal("Use /hubadmin").withStyle(ChatFormatting.GRAY),
                Component.literal("linkportal").withStyle(ChatFormatting.GRAY)
            };
        }

        ServerStatusInfo info = ServerHubMod.getServerStatus(portal.targetServerId);
        Component line1 = Component.literal(info != null ? info.displayName() : portal.targetServerId)
                .withStyle(ChatFormatting.WHITE);

        Component line2;
        if (info == null) {
            line2 = Component.literal("OFFLINE").withStyle(ChatFormatting.RED);
        } else if (info.isOnline()) {
            line2 = Component.literal("ONLINE (" + info.onlinePlayers() + "/" + info.maxPlayers() + ")")
                    .withStyle(ChatFormatting.GREEN);
        } else if ("RESTARTING".equalsIgnoreCase(info.status())) {
            line2 = Component.literal("RESTARTING").withStyle(ChatFormatting.YELLOW);
        } else {
            line2 = Component.literal(info.status()).withStyle(ChatFormatting.RED);
        }

        Component line3 = Component.literal("Walk in to join").withStyle(ChatFormatting.GRAY);
        return new Component[] {line0, line1, line2, line3};
    }

    private static void applySignText(
            ServerLevel level, BlockPos pos, SignBlockEntity sign, Component[] desiredLines) {
        SignText current = sign.getFrontText();
        boolean alreadyMatches = current.getColor() == DyeColor.WHITE
                && current.hasGlowingText()
                && sign.isWaxed()
                && Objects.equals(current.getMessage(0, false), desiredLines[0])
                && Objects.equals(current.getMessage(1, false), desiredLines[1])
                && Objects.equals(current.getMessage(2, false), desiredLines[2])
                && Objects.equals(current.getMessage(3, false), desiredLines[3]);
        if (alreadyMatches) {
            return;
        }

        SignText updated = new SignText()
                .setColor(DyeColor.WHITE)
                .setHasGlowingText(true)
                .setMessage(0, desiredLines[0])
                .setMessage(1, desiredLines[1])
                .setMessage(2, desiredLines[2])
                .setMessage(3, desiredLines[3]);
        sign.setText(updated, true);
        sign.setWaxed(true);
        sign.setChanged();
        BlockState state = sign.getBlockState();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    private record PortalSpec(
            String name,
            int centerX,
            int centerZ,
            boolean widthAlongX,
            Direction inwardFacing,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {}
}
