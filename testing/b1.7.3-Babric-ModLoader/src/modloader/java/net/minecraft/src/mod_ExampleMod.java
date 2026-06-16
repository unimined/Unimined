package net.minecraft.src;

import com.example.ExampleMod;

public class mod_ExampleMod extends BaseMod {
    @Override
    public String Version() {
        return null;
    }

    @Override
    public void ModsLoaded() {
        ExampleMod.init();
    }
}
