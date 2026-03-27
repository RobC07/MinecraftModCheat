package com.snakefarm.macro;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects snake entities in the Living Cave.
 *
 * Hypixel SkyBlock snakes in the Rift Living Cave are rendered as armor stands
 * with specific name patterns. The snake body segments are individual armor stands
 * forming a chain. We detect them by scanning for armor stands with snake-related
 * custom names within a configurable radius.
 */
public class SnakeDetector {
    // Search radius around the player
    private static final double SCAN_RADIUS = 30.0;

    // Name patterns that identify snake entities (Hypixel uses color-coded names)
    private static final String[] SNAKE_NAME_PATTERNS = {
            "snake", "Snake", "SNAKE",
            "Rift Snake", "Living Cave Snake",
            // Hypixel often uses section symbol color codes
    };

    /**
     * Finds all snake-related armor stands within scan radius.
     */
    public static List<ArmorStandEntity> findSnakeEntities(MinecraftClient client) {
        List<ArmorStandEntity> snakes = new ArrayList<>();
        if (client.world == null || client.player == null) return snakes;

        ClientWorld world = client.world;
        Vec3d playerPos = client.player.getPos();

        Box scanBox = new Box(
                playerPos.x - SCAN_RADIUS, playerPos.y - SCAN_RADIUS, playerPos.z - SCAN_RADIUS,
                playerPos.x + SCAN_RADIUS, playerPos.y + SCAN_RADIUS, playerPos.z + SCAN_RADIUS
        );

        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ArmorStandEntity armorStand)) continue;
            if (!entity.isAlive()) continue;
            if (!scanBox.contains(entity.getPos())) continue;

            if (isSnakeEntity(armorStand)) {
                snakes.add(armorStand);
            }
        }

        return snakes;
    }

    /**
     * Finds the nearest snake entity to the player.
     */
    public static Optional<ArmorStandEntity> findNearestSnake(MinecraftClient client) {
        if (client.player == null) return Optional.empty();
        Vec3d playerPos = client.player.getPos();

        return findSnakeEntities(client).stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(playerPos.x, playerPos.y, playerPos.z)));
    }

    /**
     * Finds all body segment armor stands belonging to the same snake,
     * sorted by distance (closest segment first for tail mining).
     */
    public static List<ArmorStandEntity> findSnakeSegments(MinecraftClient client, ArmorStandEntity headOrAny) {
        List<ArmorStandEntity> segments = new ArrayList<>();
        if (client.world == null) return segments;

        Vec3d snakePos = headOrAny.getPos();
        // Snake segments are typically within 5 blocks of each other
        double segmentRadius = 8.0;
        Box segmentBox = new Box(
                snakePos.x - segmentRadius, snakePos.y - segmentRadius, snakePos.z - segmentRadius,
                snakePos.x + segmentRadius, snakePos.y + segmentRadius, snakePos.z + segmentRadius
        );

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ArmorStandEntity armorStand)) continue;
            if (!entity.isAlive()) continue;
            if (!segmentBox.contains(entity.getPos())) continue;
            if (isSnakeEntity(armorStand)) {
                segments.add(armorStand);
            }
        }

        // Sort by distance from the player (mine closest/tail segments first)
        if (client.player != null) {
            Vec3d playerPos = client.player.getPos();
            segments.sort(Comparator.comparingDouble(
                    e -> e.squaredDistanceTo(playerPos.x, playerPos.y, playerPos.z)));
        }

        return segments;
    }

    /**
     * Checks if an armor stand is a snake entity.
     * Uses name matching and equipment checks.
     */
    private static boolean isSnakeEntity(ArmorStandEntity armorStand) {
        // Check custom name
        if (armorStand.hasCustomName()) {
            Text name = armorStand.getCustomName();
            if (name != null) {
                String nameStr = name.getString().toLowerCase();
                // Check for known snake name patterns
                if (nameStr.contains("snake")) return true;
                if (nameStr.contains("serpent")) return true;
                if (nameStr.contains("slitherer")) return true;
            }
        }

        // Hypixel sometimes uses invisible armor stands with specific equipment
        // as snake body segments. Check if the armor stand has colored leather armor
        // (snake segments often wear dyed leather armor for the snake body appearance)
        if (armorStand.isInvisible() && armorStand.isMarker()) {
            // Invisible marker armor stands with equipment could be snake segments
            // This heuristic may need tuning based on actual Hypixel data
            return false; // Conservative: only match named entities by default
        }

        return false;
    }

    /**
     * Gets the block position nearest to a snake entity that could be
     * a mineable snake block (tail segment).
     */
    public static Optional<BlockPos> findMineableSnakeBlock(MinecraftClient client, ArmorStandEntity segment) {
        if (client.world == null) return Optional.empty();

        BlockPos entityBlock = segment.getBlockPos();

        // Check the block at and around the armor stand position
        // Snake tail blocks are placed at/near the armor stand positions
        BlockPos[] candidates = {
                entityBlock,
                entityBlock.down(),
                entityBlock.up(),
                entityBlock.north(),
                entityBlock.south(),
                entityBlock.east(),
                entityBlock.west()
        };

        for (BlockPos pos : candidates) {
            if (!client.world.getBlockState(pos).isAir()) {
                // Found a non-air block near the snake segment
                return Optional.of(pos);
            }
        }

        return Optional.empty();
    }
}
