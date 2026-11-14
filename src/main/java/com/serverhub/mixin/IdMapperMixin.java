package com.serverhub.mixin;

import com.serverhub.network.BlockStateRemapper;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IdMapper.class)
public abstract class IdMapperMixin<T> {
    @Inject(method = "getId(Ljava/lang/Object;)I", at = @At("RETURN"), cancellable = true)
    private void serverhub$remapBlockStateId(T value, CallbackInfoReturnable<Integer> cir) {
        if ((Object) this == Block.BLOCK_STATE_REGISTRY) {
            int[] table = BlockStateRemapper.getActiveEncodeRemap();
            if (table != null) {
                cir.setReturnValue(BlockStateRemapper.remapId(cir.getReturnValueI(), table));
            }
        }
    }
}
