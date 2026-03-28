package com.snakefarm.macro;

import com.snakefarm.SnakeFarmMod;
import com.snakefarm.util.ChatUtils;
import com.snakefarm.util.PosHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * Core state machine for the snake farming macro.
 *
 * Flow:
 *   IDLE -> SCANNING -> PATHFINDING -> APPROACHING -> STUNNING -> MINING
 *     ^                                                             |
 *     |_____________ (snake fully mined) __________________________|
 *
 * The macro repeats: find snake -> walk to it -> stun -> mine tail -> stun -> mine ...
 * until the entire snake is consumed, then scans for the next one.
 */
public class SnakeFarmMacro {

    public enum State {
        IDLE("Idle"),
        SCANNING("Scanning for snakes..."),
        PATHFINDING("Walking to snake..."),
        STUNNING("Stunning snake..."),
        MINING("Mining snake tail..."),
        WAITING("Waiting for cooldown...");

        public final String display;
        State(String display) { this.display = display; }
    }

    private boolean running = false;
    private State state = State.IDLE;

    private final PathNavigator navigator = new PathNavigator();
    private final PlayerController controller = new PlayerController();

    // Current target snake
    private ArmorStandEntity targetSnake = null;
    private BlockPos targetMineBlock = null;

    // Timing
    private int tickCounter = 0;
    private int scanCooldown = 0;
    private int stunCooldown = 0;
    private int waitTicks = 0;

    // Stats
    private int snakesKilled = 0;
    private int blocksMined = 0;

    // Config
    private static final int SCAN_INTERVAL = 20;         // Scan every 1 second
    private static final int STUN_DURATION = 4;           // Ticks to wait after stun before mining
    private static final int STUN_COOLDOWN = 25;          // Ticks between stuns (~1.25s)
    private static final int MAX_MINE_TICKS = 100;        // Max ticks mining one block before re-stun

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

    /**
     * Main tick handler - called every client tick when the macro is running.
     */
    public void tick(MinecraftClient client) {
        if (!running || client.player == null || client.world == null) return;

        tickCounter++;

        switch (state) {
            case SCANNING -> tickScanning(client);
            case PATHFINDING -> tickPathfinding(client);
            case STUNNING -> tickStunning(client);
            case MINING -> tickMining(client);
            case WAITING -> tickWaiting(client);
            case IDLE -> { /* do nothing */ }
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

        Optional<ArmorStandEntity> nearest = SnakeDetector.findNearestSnake(client);

        if (nearest.isPresent()) {
            targetSnake = nearest.get();
            state = State.PATHFINDING;
            navigator.setTarget(targetSnake);
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Found snake! Moving to it...");
            SnakeFarmMod.LOGGER.info("[SnakeFarm] Snake found at {}", PosHelper.getPos(targetSnake));
        } else {
            // No snake found, wait and try again
            scanCooldown = SCAN_INTERVAL;
        }
    }

    // ========================
    // State: PATHFINDING
    // ========================
    private void tickPathfinding(MinecraftClient client) {
        // Check if target is still valid
        if (targetSnake == null || !targetSnake.isAlive()) {
            ChatUtils.sendActionBar("\u00a7e[SnakeFarm] Snake lost. Rescanning...");
            state = State.SCANNING;
            navigator.stop();
            return;
        }

        // Update target position (snakes move!)
        navigator.setTarget(targetSnake);

        boolean arrived = navigator.tick(client);

        if (arrived) {
            navigator.stop();
            state = State.STUNNING;
            stunCooldown = 0;
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Reached snake! Stunning...");
        }

        // If stuck, try to find another snake
        if (navigator.isStuck()) {
            ChatUtils.sendActionBar("\u00a7c[SnakeFarm] Stuck! Finding new target...");
            navigator.stop();
            targetSnake = null;
            state = State.SCANNING;
        }
    }

    // ========================
    // State: STUNNING
    // ========================
    private void tickStunning(MinecraftClient client) {
        if (targetSnake == null || !targetSnake.isAlive()) {
            snakeCleared();
            return;
        }

        // Check if we're still in range
        if (client.player.squaredDistanceTo(targetSnake) > 5.0 * 5.0) {
            // Snake moved away, pathfind again
            state = State.PATHFINDING;
            navigator.setTarget(targetSnake);
            return;
        }

        // Attack the snake to stun it
        controller.attackEntity(client, targetSnake);
        ChatUtils.sendActionBar("\u00a7b[SnakeFarm] Stunned! Mining tail...");

        // Transition to mining after a brief delay
        state = State.WAITING;
        waitTicks = STUN_DURATION;
    }

    // ========================
    // State: WAITING (post-stun delay before mining)
    // ========================
    private void tickWaiting(MinecraftClient client) {
        waitTicks--;
        if (waitTicks <= 0) {
            // Find the mineable block near the snake
            if (targetSnake != null && targetSnake.isAlive()) {
                findAndStartMining(client);
            } else {
                snakeCleared();
            }
        }
    }

    // ========================
    // State: MINING
    // ========================
    private void tickMining(MinecraftClient client) {
        if (targetSnake == null || !targetSnake.isAlive()) {
            controller.stopMining(client);
            snakeCleared();
            return;
        }

        if (targetMineBlock == null) {
            // No block to mine, re-stun
            state = State.STUNNING;
            return;
        }

        mineTicks++;

        // Check if block is already gone (broken)
        if (client.world.getBlockState(targetMineBlock).isAir()) {
            blocksMined++;
            controller.stopMining(client);
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Block mined! (" + blocksMined + " total) Re-stunning...");

            // Need to stun again before mining next block
            stunCooldown = STUN_COOLDOWN;
            mineTicks = 0;
            targetMineBlock = null;
            state = State.STUNNING;
            return;
        }

        // Mine the block
        boolean broken = controller.mineBlock(client, targetMineBlock);
        if (broken) {
            blocksMined++;
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Block mined! (" + blocksMined + " total)");
            mineTicks = 0;
            targetMineBlock = null;
            // Re-stun for next segment
            state = State.STUNNING;
        }

        // If mining takes too long, re-stun (stun may have worn off)
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

    private void findAndStartMining(MinecraftClient client) {
        // Find a mineable block near the snake
        Optional<BlockPos> block = SnakeDetector.findMineableSnakeBlock(client, targetSnake);
        if (block.isPresent()) {
            targetMineBlock = block.get();
            mineTicks = 0;
            state = State.MINING;
        } else {
            // No mineable block found — try getting closer or re-stun
            // The snake segments themselves may be the target
            // Try attacking the entity directly as an alternative
            state = State.STUNNING;
        }
    }

    private void snakeCleared() {
        snakesKilled++;
        ChatUtils.send("\u00a7a[SnakeFarm] Snake cleared! Total: " + snakesKilled + " snakes, " + blocksMined + " blocks");
        targetSnake = null;
        targetMineBlock = null;
        mineTicks = 0;
        state = State.SCANNING;
        scanCooldown = 10; // Brief pause before scanning for next snake
    }

    private void resetState() {
        targetSnake = null;
        targetMineBlock = null;
        tickCounter = 0;
        scanCooldown = 0;
        stunCooldown = 0;
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

        // Background
        context.fill(x - 2, y - 2, x + 140, y + 52, 0x80000000);

        // Title
        context.drawText(client.textRenderer, "\u00a7b\u00a7l[SnakeFarm]", x, y, color, true);
        y += 12;

        // State
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

        // Stats
        context.drawText(client.textRenderer, "\u00a77Snakes: \u00a7f" + snakesKilled, x, y, color, true);
        y += 10;
        context.drawText(client.textRenderer, "\u00a77Blocks: \u00a7f" + blocksMined, x, y, color, true);
    }
}
