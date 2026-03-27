package com.snakefarm.macro;

import com.snakefarm.util.PosHelper;
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

public class SnakeDetector {
    private static final double SCAN_RADIUS = 30.0;

    public static List<ArmorStandEntity> findSnakeEntities(MinecraftClient client) {
        List<ArmorStandEntity> snakes = new ArrayList<>();
        if (client.world == null || client.player == null) return snakes;

        ClientWorld world = client.world;
        Vec3d playerPos = PosHelper.getPos(client.player);

        Box scanBox = new Box(
                playerPos.x - SCAN_RADIUS, playerPos.y - SCAN_RADIUS, playerPos.z - SCAN_RADIUS,
                playerPos.x + SCAN_RADIUS, playerPos.y + SCAN_RADIUS, playerPos.z + SCAN_RADIUS
        );

        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ArmorStandEntity armorStand)) continue;
            if (!entity.isAlive()) continue;
            if (!scanBox.contains(PosHelper.getPos(entity))) continue;

            if (isSnakeEntity(armorStand)) {
                snakes.add(armorStand);
            }
        }

        return snakes;
    }

    public static Optional<ArmorStandEntity> findNearestSnake(MinecraftClient client) {
        if (client.player == null) return Optional.empty();
        Vec3d playerPos = PosHelper.getPos(client.player);

        return findSnakeEntities(client).stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(playerPos.x, playerPos.y, playerPos.z)));
    }

    public static List<ArmorStandEntity> findSnakeSegments(MinecraftClient client, ArmorStandEntity headOrAny) {
        List<ArmorStandEntity> segments = new ArrayList<>();
        if (client.world == null) return segments;

        Vec3d snakePos = PosHelper.getPos(headOrAny);
        double segmentRadius = 8.0;
        Box segmentBox = new Box(
                snakePos.x - segmentRadius, snakePos.y - segmentRadius, snakePos.z - segmentRadius,
                snakePos.x + segmentRadius, snakePos.y + segmentRadius, snakePos.z + segmentRadius
        );

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ArmorStandEntity armorStand)) continue;
            if (!entity.isAlive()) continue;
            if (!segmentBox.contains(PosHelper.getPos(entity))) continue;
            if (isSnakeEntity(armorStand)) {
                segments.add(armorStand);
            }
        }

        if (client.player != null) {
            Vec3d playerPos = PosHelper.getPos(client.player);
            segments.sort(Comparator.comparingDouble(
                    e -> e.squaredDistanceTo(playerPos.x, playerPos.y, playerPos.z)));
        }

        return segments;
    }

    private static boolean isSnakeEntity(ArmorStandEntity armorStand) {
        if (armorStand.hasCustomName()) {
            Text name = armorStand.getCustomName();
            if (name != null) {
                String nameStr = name.getString().toLowerCase();
                if (nameStr.contains("snake")) return true;
                if (nameStr.contains("serpent")) return true;
                if (nameStr.contains("slitherer")) return true;
            }
        }

        if (armorStand.isInvisible() && armorStand.isMarker()) {
            return false;
        }

        return false;
    }

    public static Optional<BlockPos> findMineableSnakeBlock(MinecraftClient client, ArmorStandEntity segment) {
        if (client.world == null) return Optional.empty();

        BlockPos entityBlock = segment.getBlockPos();

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
                return Optional.of(pos);
            }
        }

        return Optional.empty();
    }
}
