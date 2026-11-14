package com.serverhub.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ClientModTracker;
import com.serverhub.handshake.RegistryManifests;
import com.serverhub.network.BlockStateRemapper;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.Registry;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.TagNetworkSerialization;
import org.slf4j.Logger;
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
    private static final Logger SERVERHUB_LOGGER = LogUtils.getLogger();

    @Unique
    private static final int BASE_ENTITY_DATA_ID_LIMIT = 8;

    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    @Final
    protected Connection connection;

    @Unique
    private boolean serverhub$blockRemapInitialized;

    @Unique
    @Nullable
    private int[] serverhub$blockRemapTable;

    @Unique
    private boolean serverhub$sendingRewritten;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener);

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void serverhub$skipRecipePacketsOnHub(
            Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if (this.serverhub$sendingRewritten || !HubConfig.get().isHubRole()) {
            return;
        }
        if (packet instanceof ClientboundUpdateRecipesPacket
                || packet instanceof ClientboundRecipePacket) {
            ci.cancel();
            return;
        }
        if (packet instanceof ClientboundUpdateTagsPacket tagsPacket) {
            GameProfile owner = ((ServerCommonPacketListenerImpl) (Object) this).getOwner();
            RegistryManifests.Manifest manifest =
                    owner != null ? RegistryManifests.forPlayer(owner.getId()) : null;
            if (manifest != null) {
                Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload>
                        merged = RegistryManifests.mergeTags(tagsPacket.getTags(), manifest);
                this.serverhub$sendingRewritten = true;
                try {
                    this.send(new ClientboundUpdateTagsPacket(merged), listener);
                } finally {
                    this.serverhub$sendingRewritten = false;
                }
                ci.cancel();
                return;
            }
        }
        if ((Object) this instanceof ServerGamePacketListenerImpl gameListener) {
            int[] remapTable = serverhub$ensureBlockRemapTable(gameListener);
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket
                    && remapTable != null) {
                serverhub$remapChunkPacket(chunkPacket, remapTable);
            }
            Packet<?> sanitized =
                    serverhub$rewritePlayPacket(gameListener, packet, remapTable);
            if (sanitized == null) {
                ci.cancel();
            } else if (sanitized != packet) {
                this.serverhub$sendingRewritten = true;
                try {
                    this.send(sanitized, listener);
                } finally {
                    this.serverhub$sendingRewritten = false;
                }
                ci.cancel();
            }
        }
    }

    @Unique
    private static void serverhub$remapChunkPacket(
            ClientboundLevelChunkWithLightPacket chunkPacket, int[] remapTable) {
        ClientboundLevelChunkPacketDataMixin chunkData =
                (ClientboundLevelChunkPacketDataMixin) (Object) chunkPacket.getChunkData();
        chunkData.serverhub$setBuffer(
                BlockStateRemapper.remapChunkDataBuffer(
                        chunkData.serverhub$getBuffer(), remapTable));
    }

    @Unique
    @Nullable
    private int[] serverhub$ensureBlockRemapTable(ServerGamePacketListenerImpl gameListener) {
        if (!this.serverhub$blockRemapInitialized && gameListener.player != null) {
            this.serverhub$blockRemapInitialized = true;
            var clientMods = ClientModTracker.getClientMods(gameListener.player.getUUID());
            this.serverhub$blockRemapTable =
                    BlockStateRemapper.getRemapForPlayer(gameListener.player.getUUID());
            SERVERHUB_LOGGER.info(
                    "[ServerHub] BlockState remap for {} ({}): mods={}, cataclysm={}, amendments={}, active={}",
                    gameListener.player.getGameProfile().getName(),
                    gameListener.player.getUUID(),
                    clientMods.size(),
                    clientMods.contains("cataclysm"),
                    clientMods.contains("amendments"),
                    this.serverhub$blockRemapTable != null);
            Channel channel = this.connection != null ? this.connection.channel() : null;
            if (channel != null && this.serverhub$blockRemapTable != null) {
                channel.attr(BlockStateRemapper.CHANNEL_REMAP_KEY)
                        .set(this.serverhub$blockRemapTable);
            }
        }
        return this.serverhub$blockRemapTable;
    }

    @Unique
    @Nullable
    private Packet<?> serverhub$rewritePlayPacket(
            ServerGamePacketListenerImpl gameListener,
            Packet<?> packet,
            @Nullable int[] remapTable) {
        if (packet instanceof ClientboundSetEntityDataPacket dataPacket) {
            return serverhub$filterEntityData(gameListener, dataPacket);
        }
        if (packet instanceof ClientboundLevelEventPacket levelEvent
                && remapTable != null
                && levelEvent.getType() == 2001) {
            return new ClientboundLevelEventPacket(
                    2001,
                    levelEvent.getPos(),
                    BlockStateRemapper.remapId(levelEvent.getData(), remapTable),
                    levelEvent.isGlobalEvent());
        }
        if (packet instanceof ClientboundBundlePacket bundlePacket) {
            boolean needsSanitizing = false;
            for (Packet<? super ClientGamePacketListener> subPacket : bundlePacket.subPackets()) {
                if (subPacket instanceof ClientboundSetEntityDataPacket dataPacket
                        && serverhub$filterEntityData(gameListener, dataPacket) != dataPacket) {
                    needsSanitizing = true;
                    break;
                }
                if (subPacket instanceof ClientboundLevelEventPacket levelEvent
                        && remapTable != null
                        && levelEvent.getType() == 2001) {
                    needsSanitizing = true;
                    break;
                }
            }
            if (!needsSanitizing) {
                if (remapTable != null) {
                    for (Packet<? super ClientGamePacketListener> subPacket :
                            bundlePacket.subPackets()) {
                        if (subPacket instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                            serverhub$remapChunkPacket(chunkPacket, remapTable);
                        }
                    }
                }
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
                } else if (subPacket instanceof ClientboundLevelEventPacket levelEvent
                        && remapTable != null
                        && levelEvent.getType() == 2001) {
                    sanitized.add(
                            new ClientboundLevelEventPacket(
                                    2001,
                                    levelEvent.getPos(),
                                    BlockStateRemapper.remapId(levelEvent.getData(), remapTable),
                                    levelEvent.isGlobalEvent()));
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
