package com.example;

public class Example {
    public static String mcVersion() {
        return net.minecraft.client.MinecraftClient.getInstance().getGameVersion();
    }
    public static boolean loaderPresent() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-api");
    }
}
