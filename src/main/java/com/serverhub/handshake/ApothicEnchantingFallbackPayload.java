package com.serverhub.handshake;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Minimal {@code apothic_enchanting:enchantment_info} payload so Apothic Enchanting initializes its
 * client map with a single entry and falls back safely for all other enchantments on the Hub.
 */
public record ApothicEnchantingFallbackPayload() implements CustomPacketPayload {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("apothic_enchanting", "enchantment_info");
    public static final Type<ApothicEnchantingFallbackPayload> TYPE = new Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, ApothicEnchantingFallbackPayload> STREAM_CODEC =
            StreamCodec.of(ApothicEnchantingFallbackPayload::encode, buf -> new ApothicEnchantingFallbackPayload());

    private static void encode(FriendlyByteBuf rawBuf, ApothicEnchantingFallbackPayload payload) {
        if (!(rawBuf instanceof RegistryFriendlyByteBuf buf)) {
            rawBuf.writeVarInt(0);
            return;
        }
        var lookupOpt = buf.registryAccess().lookup(Registries.ENCHANTMENT);
        if (lookupOpt.isEmpty()) {
            buf.writeVarInt(0);
            return;
        }
        var sharpnessOpt = lookupOpt.get().get(Enchantments.SHARPNESS);
        if (sharpnessOpt.isEmpty()) {
            buf.writeVarInt(0);
            return;
        }
        Holder<Enchantment> sharpness = sharpnessOpt.get();
        int maxLevel = sharpness.value().getMaxLevel();
        buf.writeVarInt(1);
        Enchantment.STREAM_CODEC.encode(buf, sharpness);
        Enchantment.STREAM_CODEC.encode(buf, sharpness);
        buf.writeVarInt(maxLevel);
        buf.writeVarInt(maxLevel);
        buf.writeVarInt(-1);
        buf.writeByte(1); // PowerFunction.Type.DEFAULT_MAX
        buf.writeByte(0); // PowerFunction.Type.DEFAULT_MIN
        Enchantment.STREAM_CODEC.encode(buf, sharpness);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
