package com.serverhub.mixin;

import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ApothicEnchantingFallbackPayload;
import com.serverhub.handshake.ClientModTracker;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.neoforged.neoforge.network.filters.NetworkFilters;
import net.neoforged.neoforge.network.payload.CommonRegisterPayload;
import net.neoforged.neoforge.network.payload.CommonVersionPayload;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryComponent;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkSetupFailedPayload;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryMixin {
    @Shadow
    @Final
    private static Map<ResourceLocation, StreamCodec<FriendlyByteBuf, ? extends CustomPacketPayload>> BUILTIN_PAYLOADS;

    @Shadow
    @Final
    private static Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> PAYLOAD_REGISTRATIONS;

    @Inject(
            method = "initializeNeoForgeConnection(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;Ljava/util/Map;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void serverhub$onInitializeNeoForgeConnection(
            ServerConfigurationPacketListener listener,
            Map<ConnectionProtocol, Set<ModdedNetworkQueryComponent>> clientChannels,
            CallbackInfo ci) {
        if (listener instanceof ServerCommonPacketListenerImpl commonListener
                && commonListener.getOwner() != null
                && commonListener.getOwner().getId() != null) {
            ClientModTracker.recordClientChannels(commonListener.getOwner().getId(), clientChannels);
        }

        if (HubConfig.get().isHubRole()) {
            // Accept NeoForge's own server channels (so SyncRegistries runs) plus all client channels.
            Map<ConnectionProtocol, Map<ResourceLocation, NetworkChannel>> channels = new IdentityHashMap<>();
            for (ConnectionProtocol protocol : ConnectionProtocol.values()) {
                Map<ResourceLocation, NetworkChannel> protocolMap = new HashMap<>();
                Map<ResourceLocation, PayloadRegistration<?>> serverRegs =
                        PAYLOAD_REGISTRATIONS.getOrDefault(protocol, Collections.emptyMap());
                serverRegs.forEach((id, reg) -> protocolMap.put(id, new NetworkChannel(id, reg.version())));
                for (ModdedNetworkQueryComponent comp :
                        clientChannels.getOrDefault(protocol, Collections.emptySet())) {
                    protocolMap.putIfAbsent(comp.id(), new NetworkChannel(comp.id(), comp.version()));
                }
                if (!protocolMap.isEmpty()) {
                    channels.put(protocol, Collections.unmodifiableMap(protocolMap));
                }
            }
            NetworkPayloadSetup setup = new NetworkPayloadSetup(Collections.unmodifiableMap(channels));

            ChannelAttributes.setPayloadSetup(listener.getConnection(), setup);
            ChannelAttributes.setConnectionType(listener.getConnection(), listener.getConnectionType());
            NetworkFilters.injectIfNecessary(listener.getConnection());
            listener.send(new ModdedNetworkPayload(setup));

            Set<ResourceLocation> listeningChannels = new HashSet<>(Set.of(
                    MinecraftRegisterPayload.ID,
                    MinecraftUnregisterPayload.ID,
                    ModdedNetworkQueryPayload.ID,
                    ModdedNetworkPayload.ID,
                    ModdedNetworkSetupFailedPayload.ID,
                    CommonVersionPayload.ID,
                    CommonRegisterPayload.ID));
            listeningChannels.addAll(setup.getChannels(ConnectionProtocol.CONFIGURATION).keySet());
            listener.send(new MinecraftRegisterPayload(Collections.unmodifiableSet(listeningChannels)));
            ci.cancel();
        }
    }

    @Inject(
            method = "handleModdedPayload(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void serverhub$onHandleModdedPayload(
            ServerCommonPacketListener listener,
            ServerboundCustomPayloadPacket packet,
            CallbackInfo ci) {
        if (HubConfig.get().isHubRole()
                && !serverhub$hasServerRegistration(listener.protocol(), packet.payload().type().id())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "getCodec(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/codec/StreamCodec;",
            at = @At("HEAD"),
            cancellable = true)
    private static void serverhub$onGetCodec(
            ResourceLocation id,
            ConnectionProtocol protocol,
            PacketFlow flow,
            CallbackInfoReturnable<StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>> cir) {
        if (!HubConfig.get().isHubRole()) {
            return;
        }
        if (flow == PacketFlow.CLIENTBOUND && ApothicEnchantingFallbackPayload.ID.equals(id)) {
            cir.setReturnValue(ApothicEnchantingFallbackPayload.STREAM_CODEC);
            return;
        }
        if (flow == PacketFlow.SERVERBOUND
                && !BUILTIN_PAYLOADS.containsKey(id)
                && !"minecraft".equals(id.getNamespace())
                && !serverhub$hasServerRegistration(protocol, id)) {
            // Returning null makes CustomPacketPayload fall back to DiscardedPayload without disconnect warnings.
            cir.setReturnValue(null);
        }
    }

    @Unique
    private static boolean serverhub$hasServerRegistration(ConnectionProtocol protocol, ResourceLocation id) {
        if (protocol == null || id == null) {
            return false;
        }
        return PAYLOAD_REGISTRATIONS.getOrDefault(protocol, Collections.emptyMap()).containsKey(id);
    }
}
