package com.example.fabric.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoOptionsScreen.class)
public class VideoSettingsMixin {

    @Definition(id = "id", field = "Lnet/minecraft/client/gui/widget/ButtonWidget;id:I")
    @Expression("?.id == 200")
    @Inject(method = "buttonClicked", at = @At("MIXINEXTRAS:EXPRESSION"))
    private void updateId(
            ButtonWidget button,
            CallbackInfo info
    ) {
        System.out.println("button pressed!");
    }

    @Definition(id = "save", method = "Lnet/minecraft/client/options/GameOptions;save()V")
    @Expression("?.save()")
    @Inject(method = "buttonClicked", at = @At("MIXINEXTRAS:EXPRESSION"))
    private void onSave(ButtonWidget button, CallbackInfo ci) {
        System.out.println("saving options!");
    }

}
