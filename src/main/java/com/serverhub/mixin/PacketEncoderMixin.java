package com.serverhub.mixin;

import com.serverhub.handshake.ClientModTracker;
import com.serverhub.network.BlockStateRemapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Activates per-channel {@link BlockStateRemapper} state during Netty packet encoding so
 * single-block, multi-block, particle, and entity-held block state IDs match the client's
 * shifted {@code Block.BLOCK_STATE_REGISTRY}.
 */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin<T extends PacketListener> {
    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("HEAD"))
    private void serverhub$pushBlockStateRemap(
            ChannelHandlerContext ctx, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        int[] table = ctx.channel().attr(BlockStateRemapper.CHANNEL_REMAP_KEY).get();
        if (table != null) {
            BlockStateRemapper.setActiveEncodeRemap(table);
        }
    }

    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/ProtocolSwapHandler;handleOutboundTerminalPacket(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V"))
    private void serverhub$popBlockStateRemap(
            ChannelHandlerContext ctx, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        BlockStateRemapper.clearActiveEncodeRemap();
        if (Boolean.TRUE.equals(ctx.channel().attr(ClientModTracker.SABLE_COMPAT_KEY).get())
                && (packet instanceof ClientboundMoveEntityPacket.Pos
                        || packet instanceof ClientboundMoveEntityPacket.PosRot
                        || packet instanceof ClientboundTeleportEntityPacket)) {
            out.writeBoolean(false);
        }
    }
}
