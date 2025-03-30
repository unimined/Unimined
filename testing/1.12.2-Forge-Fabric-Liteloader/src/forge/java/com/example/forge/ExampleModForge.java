package com.example.forge;

import com.example.ExampleMod;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = "modid")
public class ExampleModForge {

    public ExampleModForge() {
    }

    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        ExampleMod.LOGGER.info("Hello from Forge!");
    }

}
