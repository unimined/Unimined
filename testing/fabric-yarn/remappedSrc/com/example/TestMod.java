package com.example;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;

public class TestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // yarn-named classes — compiling against Identifier proves the
        // dev artifact is genuinely named-remapped, not intermediary
        Object id = new Identifier("testmod", "entry");
    }
}
