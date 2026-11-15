package com.serverhub.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("setText")
    void serverhub$setText(Component text);

    @Invoker("setBackgroundColor")
    void serverhub$setBackgroundColor(int color);
}
