package com.example;

import net.minecraft.resources.ResourceLocation;

public class UsesMojmap {
    // mojmap-named class — compiling against ResourceLocation proves the
    // dev artifact is genuinely mojmap-remapped, not obfuscated
    public static final Object ID = new ResourceLocation("test", "entry");
}
