package com.example;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExampleMod implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("modid");


    @Override
    public void onInitialize() {
        ExampleMod.LOGGER.info("Hello from Fabric!");
    }
}
