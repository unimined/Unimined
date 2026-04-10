package com.example.liteloader.mixin;

import net.minecraft.client.gui.GuiMainMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public class MainMenuMixin {

    @Inject(method = "initGui", at = @At("RETURN"))
    private void init(CallbackInfo info) {
        System.out.println("This line is printed by an example mod mixin!");
    }

}
