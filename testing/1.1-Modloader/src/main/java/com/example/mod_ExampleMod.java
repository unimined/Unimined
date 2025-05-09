package com.example;

import net.minecraft.src.BaseMod;

import java.util.logging.Logger;

public class mod_ExampleMod extends BaseMod {
    public static Logger LOGGER = Logger.getLogger("ExampleMod");

    @Override
    public String getVersion() {
        return null;
    }

    @Override
    public void load() {
        LOGGER.info("Hello from Minecraft!");
    }

    boolean firstTick = true;


}
