package com.example.forge;

import com.example.ExampleMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("modid")
public class ExampleModForge {

    public ExampleModForge() {
    }

    @SubscribeEvent
    public void onInit(FMLCommonSetupEvent event) {
        ExampleMod.LOGGER.info("Hello from Forge!");
    }

}
