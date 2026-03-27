package com.snakefarm.util;

import com.snakefarm.macro.SnakeFarmMacro;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KeybindHandler {
    private static KeyBinding toggleKey;

    public static void register() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snakefarm.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.snakefarm"
        ));
    }

    public static void handleTick(MinecraftClient client, SnakeFarmMacro macro) {
        while (toggleKey.wasPressed()) {
            macro.toggle();
            if (client.player != null) {
                String status = macro.isRunning() ? "\u00a7aENABLED" : "\u00a7cDISABLED";
                ChatUtils.send("\u00a77[SnakeFarm] Macro " + status);
            }
        }
    }
}
