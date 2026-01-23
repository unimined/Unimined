package com.example.mixin;

import com.example.ExampleMod;
import net.minecraft.client.gui.MainMenuScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainMenuScreen.class)
public class ExampleMixin {

    @Inject(method = "init()V", at = @At("HEAD"))
    private void init(CallbackInfo info) {
        ExampleMod.LOGGER.info("This line is printed by an example mod mixin!");
    }
}
