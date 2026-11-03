package com.example;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;

public class TestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // mojmap-named class: compiling proves the dev artifact is
        // mojmap-remapped on a fabric environment (prod: intermediary)
        Object id = new ResourceLocation("testmod", "entry");
    }
}
