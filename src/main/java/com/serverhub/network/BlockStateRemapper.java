package com.serverhub.network;

import com.serverhub.handshake.ClientModTracker;
import io.netty.buffer.Unpooled;
import io.netty.util.AttributeKey;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Remaps outgoing vanilla {@link BlockState} registry IDs for modded clients whose installed mods
 * append extra {@link BlockState}s to vanilla blocks during bootstrap, shifting subsequent
 * {@code Block.BLOCK_STATE_REGISTRY} IDs on the client.
 *
 * <p>Known client-side state shifts:
 * <ul>
 *   <li>{@code cataclysm}: appends 3 {@code NoteBlockInstrument} values to {@code minecraft:note_block}
 *       (+150 states after {@code note_block}).</li>
 *   <li>{@code amendments}: adds {@code boiling} to {@code minecraft:water_cauldron} (+3 states,
 *       vanilla {@code boiling=false} at +3) and {@code waterlogged} to all 14
 *       {@link AbstractSkullBlock}s (7 {@code SkullBlock}s + 7 {@code WallSkullBlock}s:
 *       +224 + 56 = +280 states, vanilla {@code waterlogged=false} at {@code 2*i + 1}).</li>
 * </ul>
 */
public final class BlockStateRemapper {
    public static final AttributeKey<int[]> CHANNEL_REMAP_KEY =
            AttributeKey.valueOf("serverhub:block_state_remap");

    private static final int CATACLYSM_NOTE_BLOCK_EXTRA_STATES = 3 * 25 * 2;
    private static final int AMENDMENTS_CAULDRON_EXTRA_STATES = 3;
    private static final int SECTION_BLOCK_COUNT = 16 * 16 * 16;

    private static final ThreadLocal<int[]> ACTIVE_ENCODE_REMAP = new ThreadLocal<>();

    // Cached tables indexed by bitmask: bit 0 = cataclysm, bit 1 = amendments.
    private static final int[][] CACHED_TABLES = new int[4][];

    private BlockStateRemapper() {}

    @Nullable
    public static int[] getRemapForPlayer(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        Set<String> clientMods = ClientModTracker.getClientMods(playerUuid);
        boolean hasCataclysm = clientMods.contains("cataclysm");
        boolean hasAmendments = clientMods.contains("amendments");
        return getRemapTable(hasCataclysm, hasAmendments);
    }

    @Nullable
    public static int[] getRemapTable(boolean hasCataclysm, boolean hasAmendments) {
        int key = (hasCataclysm ? 1 : 0) | (hasAmendments ? 2 : 0);
        if (key == 0) {
            return null;
        }
        int[] cached = CACHED_TABLES[key];
        if (cached == null) {
            cached = buildRemapTable(hasCataclysm, hasAmendments);
            CACHED_TABLES[key] = cached;
        }
        return cached;
    }

    private static int[] buildRemapTable(boolean hasCataclysm, boolean hasAmendments) {
        int[] table = new int[Block.BLOCK_STATE_REGISTRY.size()];
        int clientId = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (hasCataclysm && block == Blocks.NOTE_BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    table[Block.getId(state)] = clientId++;
                }
                clientId += CATACLYSM_NOTE_BLOCK_EXTRA_STATES;
            } else if (hasAmendments && block == Blocks.WATER_CAULDRON) {
                clientId += AMENDMENTS_CAULDRON_EXTRA_STATES;
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    table[Block.getId(state)] = clientId++;
                }
            } else if (hasAmendments && block instanceof AbstractSkullBlock) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    clientId++;
                    table[Block.getId(state)] = clientId++;
                }
            } else {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    table[Block.getId(state)] = clientId++;
                }
            }
        }
        return table;
    }

    public static int remapId(int serverId, int[] table) {
        if (serverId >= 0 && serverId < table.length) {
            return table[serverId];
        }
        return serverId;
    }

    public static void setActiveEncodeRemap(@Nullable int[] table) {
        if (table == null) {
            ACTIVE_ENCODE_REMAP.remove();
        } else {
            ACTIVE_ENCODE_REMAP.set(table);
        }
    }

    public static void clearActiveEncodeRemap() {
        ACTIVE_ENCODE_REMAP.remove();
    }

    @Nullable
    public static int[] getActiveEncodeRemap() {
        return ACTIVE_ENCODE_REMAP.get();
    }

    public static byte[] remapChunkDataBuffer(byte[] input, int[] table) {
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(input));
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(input.length + 64));
        try {
            while (in.isReadable()) {
                // LevelChunkSection: short nonEmptyBlockCount
                out.writeShort(in.readShort());

                // PalettedContainer<BlockState>
                int blockBits = in.readUnsignedByte();
                out.writeByte(blockBits);
                if (blockBits == 0) {
                    int stateId = VarInt.read(in);
                    VarInt.write(out, remapId(stateId, table));
                    copyLongArray(in, out);
                } else if (blockBits <= 8) {
                    int paletteSize = VarInt.read(in);
                    VarInt.write(out, paletteSize);
                    for (int i = 0; i < paletteSize; i++) {
                        int stateId = VarInt.read(in);
                        VarInt.write(out, remapId(stateId, table));
                    }
                    copyLongArray(in, out);
                } else {
                    int longCount = VarInt.read(in);
                    long[] raw = new long[longCount];
                    for (int i = 0; i < longCount; i++) {
                        raw[i] = in.readLong();
                    }
                    SimpleBitStorage srcStorage =
                            new SimpleBitStorage(blockBits, SECTION_BLOCK_COUNT, raw);
                    SimpleBitStorage dstStorage =
                            new SimpleBitStorage(blockBits, SECTION_BLOCK_COUNT);
                    for (int i = 0; i < SECTION_BLOCK_COUNT; i++) {
                        dstStorage.set(i, remapId(srcStorage.get(i), table));
                    }
                    long[] remappedRaw = dstStorage.getRaw();
                    VarInt.write(out, remappedRaw.length);
                    for (long value : remappedRaw) {
                        out.writeLong(value);
                    }
                }

                // PalettedContainerRO<Holder<Biome>> (unchanged)
                int biomeBits = in.readUnsignedByte();
                out.writeByte(biomeBits);
                if (biomeBits == 0) {
                    VarInt.write(out, VarInt.read(in));
                } else if (biomeBits <= 3) {
                    int paletteSize = VarInt.read(in);
                    VarInt.write(out, paletteSize);
                    for (int i = 0; i < paletteSize; i++) {
                        VarInt.write(out, VarInt.read(in));
                    }
                }
                copyLongArray(in, out);
            }

            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } finally {
            in.release();
            out.release();
        }
    }

    private static void copyLongArray(FriendlyByteBuf in, FriendlyByteBuf out) {
        int longCount = VarInt.read(in);
        VarInt.write(out, longCount);
        if (longCount > 0) {
            out.writeBytes(in, longCount * Long.BYTES);
        }
    }
}
