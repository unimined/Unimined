package com.example.forge;

import com.example.ExampleMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod("modid")
public class ExampleModForge {

    public ExampleModForge() {
    }

    @SubscribeEvent
    public void onInit(FMLCommonSetupEvent event) {
        ExampleMod.LOGGER.info("Hello from Forge!");
    }

}
