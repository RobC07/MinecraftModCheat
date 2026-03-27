package com.snakefarm;

import com.snakefarm.macro.SnakeFarmMacro;
import com.snakefarm.util.KeybindHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnakeFarmMod implements ClientModInitializer {
    public static final String MOD_ID = "snakefarm";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SnakeFarmMacro macro;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[SnakeFarm] Initializing Snake Farm Macro...");

        macro = new SnakeFarmMacro();
        KeybindHandler.register();

        // Main tick loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeybindHandler.handleTick(client, macro);
            if (macro.isRunning()) {
                macro.tick(client);
            }
        });

        // HUD overlay
        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
            macro.renderHud(drawContext);
        });

        LOGGER.info("[SnakeFarm] Snake Farm Macro initialized. Press R-SHIFT to toggle.");
    }

    public static SnakeFarmMacro getMacro() {
        return macro;
    }
}
