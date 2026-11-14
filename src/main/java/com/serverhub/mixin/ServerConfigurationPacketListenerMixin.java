package com.serverhub.mixin;

import com.mojang.authlib.GameProfile;
import com.serverhub.config.HubConfig;
import com.serverhub.handshake.ClientModTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Syncs client-local datapack registry stubs on Hub servers so mod mixins and event handlers that
 * resolve client-side keys (such as {@code forbidden_arcanus:item_modifier}) load from the
 * client's own {@code mod_data} resources instead of failing on an empty registry.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public class ServerConfigurationPacketListenerMixin {
    @Unique
    private static final ResourceKey<Registry<Object>> FORBIDDEN_ARCANUS_ITEM_MODIFIER =
            ResourceKey.createRegistryKey(
                    ResourceLocation.fromNamespaceAndPath("forbidden_arcanus", "item_modifier"));

    @Shadow
    @Final
    private GameProfile gameProfile;

    @Inject(
            method = "handleSelectKnownPacks(Lnet/minecraft/network/protocol/configuration/ServerboundSelectKnownPacks;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;finishCurrentTask(Lnet/minecraft/server/network/ConfigurationTask$Type;)V"))
    private void serverhub$sendClientModRegistryStubs(
            ServerboundSelectKnownPacks packet, CallbackInfo ci) {
        if (!HubConfig.get().isHubRole()
                || this.gameProfile == null
                || this.gameProfile.getId() == null) {
            return;
        }

        Set<String> clientMods = ClientModTracker.getClientMods(this.gameProfile.getId());
        ServerCommonPacketListenerImpl sender = (ServerCommonPacketListenerImpl) (Object) this;

        if (clientMods.contains("forbidden_arcanus")) {
            List<RegistrySynchronization.PackedRegistryEntry> modifiers = new ArrayList<>();
            serverhub$addEmptyDataEntries(
                    modifiers,
                    "forbidden_arcanus",
                    "aquatic",
                    "demolishing",
                    "eternal",
                    "fiery",
                    "magnetized",
                    "soulbound");
            sender.send(
                    new ClientboundRegistryDataPacket(FORBIDDEN_ARCANUS_ITEM_MODIFIER, modifiers));
        }

        List<RegistrySynchronization.PackedRegistryEntry> extraEnchantments = new ArrayList<>();
        if (clientMods.contains("forbidden_arcanus")) {
            serverhub$addEmptyDataEntries(extraEnchantments, "forbidden_arcanus", "soul_looting");
        }
        if (clientMods.contains("ars_nouveau")) {
            serverhub$addEmptyDataEntries(
                    extraEnchantments, "ars_nouveau", "mana_boost", "mana_regen", "reactive");
        }
        if (clientMods.contains("ars_elemental")) {
            serverhub$addEmptyDataEntries(
                    extraEnchantments, "ars_elemental", "mirror_shield", "soulbound");
        }
        if (!extraEnchantments.isEmpty()) {
            sender.send(
                    new ClientboundRegistryDataPacket(Registries.ENCHANTMENT, extraEnchantments));
        }
    }

    @Unique
    private static void serverhub$addEmptyDataEntries(
            List<RegistrySynchronization.PackedRegistryEntry> entries,
            String namespace,
            String... paths) {
        for (String path : paths) {
            entries.add(
                    new RegistrySynchronization.PackedRegistryEntry(
                            ResourceLocation.fromNamespaceAndPath(namespace, path),
                            Optional.empty()));
        }
    }
}
