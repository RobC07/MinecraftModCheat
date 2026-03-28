package com.snakefarm.macro;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Detects snakes in the Living Cave.
 *
 * Snakes are made of blocks on walls/floors:
 * - Head: Lapis Lazuli block
 * - Body/Tail: Blue Stained Glass blocks in a chain
 *
 * The macro needs to stun the head, then mine the tail (last glass block).
 */
public class SnakeDetector {
    private static final double SCAN_RADIUS = 20.0;

    /**
     * Represents a detected snake made of blocks.
     */
    public static class Snake {
        public final BlockPos head;           // Lapis lazuli block
        public final List<BlockPos> body;     // Blue stained glass chain (ordered head->tail)

        public Snake(BlockPos head, List<BlockPos> body) {
            this.head = head;
            this.body = body;
        }

        /** Gets the last body segment (the tail to mine). */
        public BlockPos getTail() {
            if (body.isEmpty()) return head;
            return body.get(body.size() - 1);
        }

        /** Gets the center position for pathfinding. */
        public Vec3d getCenterPos() {
            return Vec3d.ofCenter(head);
        }

        public int length() {
            return 1 + body.size(); // head + body segments
        }
    }

    /**
     * Scans for all snakes within range of the player.
     */
    public static List<Snake> findSnakes(MinecraftClient client) {
        List<Snake> snakes = new ArrayList<>();
        if (client.world == null || client.player == null) return snakes;

        ClientWorld world = client.world;
        BlockPos playerPos = client.player.getBlockPos();
        int radius = (int) SCAN_RADIUS;

        // Track which lapis blocks we've already assigned to a snake
        Set<BlockPos> visitedHeads = new HashSet<>();

        // Scan for lapis lazuli blocks (snake heads)
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (visitedHeads.contains(pos)) continue;

                    BlockState state = world.getBlockState(pos);
                    if (isSnakeHead(state)) {
                        // Found a head — trace the body
                        List<BlockPos> body = traceSnakeBody(world, pos);
                        if (!body.isEmpty()) {
                            snakes.add(new Snake(pos, body));
                            visitedHeads.add(pos);
                        }
                    }
                }
            }
        }

        return snakes;
    }

    /**
     * Finds the nearest snake to the player.
     */
    public static Optional<Snake> findNearestSnake(MinecraftClient client) {
        if (client.player == null) return Optional.empty();
        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());

        return findSnakes(client).stream()
                .min(Comparator.comparingDouble(s -> playerPos.squaredDistanceTo(s.getCenterPos())));
    }

    /**
     * Traces the snake body from the head, following connected blue stained glass blocks.
     */
    private static List<BlockPos> traceSnakeBody(ClientWorld world, BlockPos head) {
        List<BlockPos> body = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        visited.add(head);

        BlockPos current = head;

        // Follow the chain of blue glass blocks
        while (true) {
            BlockPos next = findNextBodySegment(world, current, visited);
            if (next == null) break;

            body.add(next);
            visited.add(next);
            current = next;

            // Safety limit
            if (body.size() > 50) break;
        }

        return body;
    }

    /**
     * Finds the next connected blue stained glass block adjacent to the current position.
     * Checks all 6 directions (up, down, north, south, east, west).
     */
    private static BlockPos findNextBodySegment(ClientWorld world, BlockPos current, Set<BlockPos> visited) {
        BlockPos[] neighbors = {
                current.north(), current.south(),
                current.east(), current.west(),
                current.up(), current.down()
        };

        for (BlockPos neighbor : neighbors) {
            if (visited.contains(neighbor)) continue;
            BlockState state = world.getBlockState(neighbor);
            if (isSnakeBody(state)) {
                return neighbor;
            }
        }

        return null;
    }

    /**
     * Checks if a block is a snake head (lapis lazuli block).
     */
    private static boolean isSnakeHead(BlockState state) {
        return state.isOf(Blocks.LAPIS_BLOCK);
    }

    /**
     * Checks if a block is a snake body segment (blue stained glass).
     */
    private static boolean isSnakeBody(BlockState state) {
        return state.isOf(Blocks.BLUE_STAINED_GLASS);
    }

    /**
     * Checks if a block is any part of a snake.
     */
    public static boolean isSnakeBlock(BlockState state) {
        return isSnakeHead(state) || isSnakeBody(state);
    }
}
