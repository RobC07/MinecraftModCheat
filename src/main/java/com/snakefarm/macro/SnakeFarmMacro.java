package com.snakefarm.macro;

import com.snakefarm.SnakeFarmMod;
import com.snakefarm.util.ChatUtils;
import com.snakefarm.util.PosHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Core state machine for the snake farming macro.
 *
 * Snakes are blocks on walls/floors:
 * - Head = Lapis Lazuli block (stun target)
 * - Body = Blue Stained Glass blocks (mine target, start from tail)
 *
 * Flow: SCANNING -> PATHFINDING -> STUNNING -> MINING -> (repeat stun/mine) -> SCANNING
 */
public class SnakeFarmMacro {

    public enum State {
        IDLE("Idle"),
        SCANNING("Scanning for snakes..."),
        PATHFINDING("Walking to snake..."),
        STUNNING("Stunning snake head..."),
        MINING("Mining snake tail..."),
        WAITING("Waiting after stun...");

        public final String display;
        State(String display) { this.display = display; }
    }

    private boolean running = false;
    private State state = State.IDLE;

    private final PathNavigator navigator = new PathNavigator();
    private final PlayerController controller = new PlayerController();

    // Current target snake
    private SnakeDetector.Snake targetSnake = null;
    private BlockPos targetMineBlock = null;

    // Timing
    private int tickCounter = 0;
    private int scanCooldown = 0;
    private int waitTicks = 0;

    // Stats
    private int snakesKilled = 0;
    private int blocksMined = 0;

    // Config
    private static final int SCAN_INTERVAL = 20;         // Scan every 1 second
    private static final int STUN_DURATION = 6;           // Ticks to wait after stun before mining
    private static final int MAX_MINE_TICKS = 100;        // Max ticks mining one block before re-stun
    private static final double INTERACT_RANGE = 4.5;     // Block interaction range

    private int mineTicks = 0;

    public void toggle() {
        running = !running;
        if (running) {
            state = State.SCANNING;
            resetState();
            SnakeFarmMod.LOGGER.info("[SnakeFarm] Macro started");
        } else {
            stop();
            SnakeFarmMod.LOGGER.info("[SnakeFarm] Macro stopped");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public State getState() {
        return state;
    }

    public void tick(MinecraftClient client) {
        if (!running || client.player == null || client.world == null) return;

        tickCounter++;

        switch (state) {
            case SCANNING -> tickScanning(client);
            case PATHFINDING -> tickPathfinding(client);
            case STUNNING -> tickStunning(client);
            case MINING -> tickMining(client);
            case WAITING -> tickWaiting(client);
            case IDLE -> { }
        }
    }

    // ========================
    // State: SCANNING
    // ========================
    private void tickScanning(MinecraftClient client) {
        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }

        Optional<SnakeDetector.Snake> nearest = SnakeDetector.findNearestSnake(client);

        if (nearest.isPresent()) {
            targetSnake = nearest.get();
            state = State.PATHFINDING;
            navigator.setTarget(targetSnake.getCenterPos());
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Found snake! (" + targetSnake.length() + " blocks) Moving to it...");
            SnakeFarmMod.LOGGER.info("[SnakeFarm] Snake found at {} with {} body segments",
                    targetSnake.head, targetSnake.body.size());
        } else {
            scanCooldown = SCAN_INTERVAL;
        }
    }

    // ========================
    // State: PATHFINDING
    // ========================
    private void tickPathfinding(MinecraftClient client) {
        if (targetSnake == null) {
            state = State.SCANNING;
            navigator.stop();
            return;
        }

        // Check if the head block is still there
        if (!client.world.getBlockState(targetSnake.head).isOf(net.minecraft.block.Blocks.LAPIS_BLOCK)) {
            ChatUtils.sendActionBar("\u00a7e[SnakeFarm] Snake moved. Rescanning...");
            state = State.SCANNING;
            navigator.stop();
            return;
        }

        // Update nav target to the snake head
        navigator.setTarget(targetSnake.getCenterPos());
        boolean arrived = navigator.tick(client);

        // Check if we're close enough to interact
        Vec3d playerPos = PosHelper.getPos(client.player);
        double distToHead = playerPos.distanceTo(Vec3d.ofCenter(targetSnake.head));

        if (arrived || distToHead <= INTERACT_RANGE) {
            navigator.stop();
            state = State.STUNNING;
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] In range! Stunning head...");
        }

        if (navigator.isStuck()) {
            ChatUtils.sendActionBar("\u00a7c[SnakeFarm] Stuck! Finding new target...");
            navigator.stop();
            targetSnake = null;
            state = State.SCANNING;
        }
    }

    // ========================
    // State: STUNNING (hit the lapis head block)
    // ========================
    private void tickStunning(MinecraftClient client) {
        if (targetSnake == null) {
            state = State.SCANNING;
            return;
        }

        // Check head still exists
        if (!client.world.getBlockState(targetSnake.head).isOf(net.minecraft.block.Blocks.LAPIS_BLOCK)) {
            // Head gone — snake might be dead or moved, rescan
            snakeCleared();
            return;
        }

        // Check range to head
        Vec3d playerPos = PosHelper.getPos(client.player);
        if (playerPos.distanceTo(Vec3d.ofCenter(targetSnake.head)) > INTERACT_RANGE) {
            state = State.PATHFINDING;
            navigator.setTarget(targetSnake.getCenterPos());
            return;
        }

        // Hit the head block to stun the snake
        controller.lookAtBlock(client.player, targetSnake.head);
        controller.attackBlock(client, targetSnake.head);
        ChatUtils.sendActionBar("\u00a7b[SnakeFarm] Stunned! Mining tail...");

        // Wait briefly then mine
        state = State.WAITING;
        waitTicks = STUN_DURATION;
    }

    // ========================
    // State: WAITING
    // ========================
    private void tickWaiting(MinecraftClient client) {
        waitTicks--;
        if (waitTicks <= 0) {
            if (targetSnake != null) {
                // Re-trace the snake body to find current tail
                targetSnake = retrace(client);
                if (targetSnake != null && !targetSnake.body.isEmpty()) {
                    targetMineBlock = targetSnake.getTail();
                    mineTicks = 0;
                    state = State.MINING;
                } else if (targetSnake != null) {
                    // No body left, try mining the head itself
                    targetMineBlock = targetSnake.head;
                    mineTicks = 0;
                    state = State.MINING;
                } else {
                    snakeCleared();
                }
            } else {
                snakeCleared();
            }
        }
    }

    // ========================
    // State: MINING (break the tail block)
    // ========================
    private void tickMining(MinecraftClient client) {
        if (targetMineBlock == null) {
            state = State.STUNNING;
            return;
        }

        mineTicks++;

        // Check if block is already gone
        if (client.world.getBlockState(targetMineBlock).isAir()) {
            blocksMined++;
            controller.stopMining(client);
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Block mined! (" + blocksMined + " total)");

            targetMineBlock = null;
            mineTicks = 0;

            // Check if snake still has blocks left
            targetSnake = retrace(client);
            if (targetSnake == null) {
                snakeCleared();
            } else {
                // Stun again before mining next block
                state = State.STUNNING;
            }
            return;
        }

        // Check range
        Vec3d playerPos = PosHelper.getPos(client.player);
        if (playerPos.distanceTo(Vec3d.ofCenter(targetMineBlock)) > INTERACT_RANGE) {
            controller.stopMining(client);
            navigator.setTarget(Vec3d.ofCenter(targetMineBlock));
            state = State.PATHFINDING;
            return;
        }

        // Mine the block
        boolean broken = controller.mineBlock(client, targetMineBlock);
        if (broken) {
            blocksMined++;
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Block mined! (" + blocksMined + " total)");
            mineTicks = 0;
            targetMineBlock = null;

            targetSnake = retrace(client);
            if (targetSnake == null) {
                snakeCleared();
            } else {
                state = State.STUNNING;
            }
        }

        // If mining takes too long, re-stun
        if (mineTicks > MAX_MINE_TICKS) {
            controller.stopMining(client);
            mineTicks = 0;
            state = State.STUNNING;
            ChatUtils.sendActionBar("\u00a7e[SnakeFarm] Stun expired. Re-stunning...");
        }
    }

    // ========================
    // Helpers
    // ========================

    /**
     * Re-traces the snake from its head to get updated body positions.
     * Returns null if the head is gone (snake fully mined).
     */
    private SnakeDetector.Snake retrace(MinecraftClient client) {
        if (targetSnake == null) return null;
        if (!client.world.getBlockState(targetSnake.head).isOf(net.minecraft.block.Blocks.LAPIS_BLOCK)) {
            return null; // Head is gone
        }
        // Re-find snakes near the head position
        return SnakeDetector.findNearestSnake(client).orElse(null);
    }

    private void snakeCleared() {
        snakesKilled++;
        ChatUtils.send("\u00a7a[SnakeFarm] Snake cleared! Total: " + snakesKilled + " snakes, " + blocksMined + " blocks");
        targetSnake = null;
        targetMineBlock = null;
        mineTicks = 0;
        state = State.SCANNING;
        scanCooldown = 10;
    }

    private void resetState() {
        targetSnake = null;
        targetMineBlock = null;
        tickCounter = 0;
        scanCooldown = 0;
        waitTicks = 0;
        mineTicks = 0;
    }

    public void stop() {
        running = false;
        state = State.IDLE;
        navigator.stop();
        MinecraftClient client = MinecraftClient.getInstance();
        controller.stopMining(client);
        resetState();
    }

    // ========================
    // HUD Rendering
    // ========================
    public void renderHud(DrawContext context) {
        if (!running) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 4;
        int y = 4;
        int color = 0xFFFFFF;

        context.fill(x - 2, y - 2, x + 150, y + 52, 0x80000000);

        context.drawText(client.textRenderer, "\u00a7b\u00a7l[SnakeFarm]", x, y, color, true);
        y += 12;

        String stateColor = switch (state) {
            case SCANNING -> "\u00a7e";
            case PATHFINDING -> "\u00a76";
            case STUNNING -> "\u00a7c";
            case MINING -> "\u00a7a";
            case WAITING -> "\u00a77";
            default -> "\u00a7f";
        };
        context.drawText(client.textRenderer, stateColor + state.display, x, y, color, true);
        y += 12;

        context.drawText(client.textRenderer, "\u00a77Snakes: \u00a7f" + snakesKilled, x, y, color, true);
        y += 10;
        context.drawText(client.textRenderer, "\u00a77Blocks: \u00a7f" + blocksMined, x, y, color, true);
    }
}
