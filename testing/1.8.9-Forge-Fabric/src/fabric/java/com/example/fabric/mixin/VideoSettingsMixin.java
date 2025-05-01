package com.example.fabric.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiVideoSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiVideoSettings.class)
public class VideoSettingsMixin {

    @Definition(id = "id", field = "Lnet/minecraft/client/gui/GuiButton;id:I")
    @Expression("?.id == 200")
    @Inject(method = "actionPerformed", at = @At("MIXINEXTRAS:EXPRESSION"))
    private void updateId(
            GuiButton button,
            CallbackInfo info
    ) {
        System.out.println("button pressed!");
    }

    @Definition(id = "saveOptions", method = "Lnet/minecraft/client/settings/GameSettings;saveOptions()V")
    @Expression("?.saveOptions()")
    @Inject(method = "actionPerformed", at = @At("MIXINEXTRAS:EXPRESSION"))
    private void onSave(GuiButton button, CallbackInfo ci) {
        System.out.println("saving options!");
    }

}
