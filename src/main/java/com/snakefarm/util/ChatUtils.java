package com.snakefarm.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class ChatUtils {
    public static void send(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    public static void sendActionBar(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
}
