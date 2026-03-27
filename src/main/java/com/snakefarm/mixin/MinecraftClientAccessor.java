package com.snakefarm.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin to expose the attack cooldown ticker for precise timing.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("attackCooldown")
    int getAttackCooldown();

    @Accessor("attackCooldown")
    void setAttackCooldown(int cooldown);
}
