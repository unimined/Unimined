package com.example;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * e2e rename fixture — references mojmap members so remapSourcesJar
 * produces observable renamed output (mojmap -> srg on the prod jar).
 */
public class ItemProbe {
    static int countOf(ItemStack stack) {
        return stack.getCount();
    }

    static ItemStack stack() {
        return new ItemStack(Items.STONE);
    }
}
