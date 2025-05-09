package com.example.transform;

import com.example.mod_ExampleMod;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.src.GuiMainMenu;

@CTransformer(GuiMainMenu.class)
public class GuiMainMenuTransform {

    @CInject(method = {"initGui"}, target = @CTarget("HEAD"))
    public void onInitGui(InjectionCallback callback) {
        mod_ExampleMod.LOGGER.info("This is the main menu!");
    }
}
