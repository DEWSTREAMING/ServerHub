package com.serverhub.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundLevelChunkPacketData.class)
public interface ClientboundLevelChunkPacketDataMixin {
    @Accessor("buffer")
    byte[] serverhub$getBuffer();

    @Mutable
    @Accessor("buffer")
    void serverhub$setBuffer(byte[] buffer);
}
