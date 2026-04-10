package com.example;

import java.util.logging.Logger;

public class ExampleMod {
    public static Logger LOGGER = Logger.getLogger("ExampleMod");

    public static void init() {
        LOGGER.info("Hello from Minecraft!");
    }
}
