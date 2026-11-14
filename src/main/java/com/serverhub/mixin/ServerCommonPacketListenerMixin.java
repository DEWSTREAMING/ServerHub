package com.serverhub.mixin;

import com.serverhub.config.HubConfig;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses recipe sync packets and strips non-player subclass {@link SynchedEntityData}
 * entries on Hub servers so modpack clients do not fail on client-side recipe plugins,
 * mismatched extended {@code RecipeBookType} decoding, or mod-shifted entity data IDs.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin {
    @Unique
    private static final int BASE_ENTITY_DATA_ID_LIMIT = 8;

    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener);

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void serverhub$skipRecipePacketsOnHub(
            Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (!HubConfig.get().isHubRole()) {
            return;
        }
        if (packet instanceof ClientboundUpdateRecipesPacket
                || packet instanceof ClientboundRecipePacket) {
            ci.cancel();
            return;
        }
        if ((Object) this instanceof ServerGamePacketListenerImpl gameListener) {
            Packet<?> sanitized = serverhub$sanitizeEntityDataPacket(gameListener, packet);
            if (sanitized == null) {
                ci.cancel();
            } else if (sanitized != packet) {
                this.send(sanitized, listener);
                ci.cancel();
            }
        }
    }

    @Unique
    @Nullable
    private Packet<?> serverhub$sanitizeEntityDataPacket(
            ServerGamePacketListenerImpl gameListener, Packet<?> packet) {
        if (packet instanceof ClientboundSetEntityDataPacket dataPacket) {
            return serverhub$filterEntityData(gameListener, dataPacket);
        }
        if (packet instanceof ClientboundBundlePacket bundlePacket) {
            boolean needsSanitizing = false;
            for (Packet<? super ClientGamePacketListener> subPacket : bundlePacket.subPackets()) {
                if (subPacket instanceof ClientboundSetEntityDataPacket dataPacket
                        && serverhub$filterEntityData(gameListener, dataPacket) != dataPacket) {
                    needsSanitizing = true;
                    break;
                }
            }
            if (!needsSanitizing) {
                return bundlePacket;
            }
            List<Packet<? super ClientGamePacketListener>> sanitized = new ArrayList<>();
            for (Packet<? super ClientGamePacketListener> subPacket : bundlePacket.subPackets()) {
                if (subPacket instanceof ClientboundSetEntityDataPacket dataPacket) {
                    ClientboundSetEntityDataPacket filtered =
                            serverhub$filterEntityData(gameListener, dataPacket);
                    if (filtered != null) {
                        sanitized.add(filtered);
                    }
                } else {
                    sanitized.add(subPacket);
                }
            }
            return sanitized.isEmpty() ? null : new ClientboundBundlePacket(sanitized);
        }
        return packet;
    }

    @Unique
    @Nullable
    private ClientboundSetEntityDataPacket serverhub$filterEntityData(
            ServerGamePacketListenerImpl gameListener, ClientboundSetEntityDataPacket dataPacket) {
        if (serverhub$isPlayerEntity(gameListener, dataPacket.id())) {
            return dataPacket;
        }
        boolean hasExtraField = false;
        for (SynchedEntityData.DataValue<?> item : dataPacket.packedItems()) {
            if (item.id() >= BASE_ENTITY_DATA_ID_LIMIT) {
                hasExtraField = true;
                break;
            }
        }
        if (!hasExtraField) {
            return dataPacket;
        }
        List<SynchedEntityData.DataValue<?>> kept = new ArrayList<>();
        for (SynchedEntityData.DataValue<?> item : dataPacket.packedItems()) {
            if (item.id() < BASE_ENTITY_DATA_ID_LIMIT) {
                kept.add(item);
            }
        }
        return kept.isEmpty() ? null : new ClientboundSetEntityDataPacket(dataPacket.id(), kept);
    }

    @Unique
    private boolean serverhub$isPlayerEntity(
            ServerGamePacketListenerImpl gameListener, int entityId) {
        if (gameListener.player != null && gameListener.player.getId() == entityId) {
            return true;
        }
        if (this.server.getPlayerList() != null) {
            for (ServerPlayer online : this.server.getPlayerList().getPlayers()) {
                if (online.getId() == entityId) {
                    return true;
                }
            }
        }
        return false;
    }
}
