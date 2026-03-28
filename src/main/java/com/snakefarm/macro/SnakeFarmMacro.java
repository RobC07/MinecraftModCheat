package com.snakefarm.macro;

import com.snakefarm.SnakeFarmMod;
import com.snakefarm.util.ChatUtils;
import com.snakefarm.util.PosHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Core state machine for the snake farming macro.
 *
 * Snake mechanics (from user + SkyHanni source):
 * - Snakes are block chains: Lapis head + Blue Stained Glass body
 * - Snakes have states: SPAWNING, ACTIVE, CALM, NOT_TOUCHING_AIR
 * - To farm: right-click tail with slot 2 item to stun → switch to slot 1 → mine tail
 * - Repeat stun→mine from tail to head until entire snake is consumed
 *
 * Flow:
 *   SCANNING → PATHFINDING → ROTATING_TO_TAIL → STUNNING → MINING →
 *     (tail mined) → ROTATING_TO_TAIL → STUNNING → MINING → ...
 *     (all blocks mined) → SCANNING
 */
public class SnakeFarmMacro {

    public enum State {
        IDLE("Idle"),
        SCANNING("Scanning for snakes..."),
        PATHFINDING("Walking to snake..."),
        ROTATING_TO_TAIL("Looking at tail..."),
        STUNNING("Stunning snake..."),
        SWITCHING_TO_MINE("Switching to pickaxe..."),
        MINING("Mining tail block..."),
        SWITCHING_TO_STUN("Switching to stun item...");

        public final String display;
        State(String display) { this.display = display; }
    }

    private boolean running = false;
    private State state = State.IDLE;

    private final PathNavigator navigator = new PathNavigator();
    private final PlayerController controller = new PlayerController();

    // Current target
    private SnakeDetector.Snake targetSnake = null;
    private BlockPos currentTail = null;

    // Timing
    private int tickCounter = 0;
    private int scanCooldown = 0;
    private int stunTicks = 0;
    private int switchTicks = 0;
    private int mineTicks = 0;
    private int rotateTicks = 0;

    // Stats
    private int snakesKilled = 0;
    private int blocksMined = 0;

    // Config — hotbar slots (0-indexed)
    private static final int MINE_SLOT = 0;     // Slot 1 = pickaxe
    private static final int STUN_SLOT = 1;     // Slot 2 = stun item

    // Timing config
    private static final int SCAN_INTERVAL = 20;          // 1 second between scans
    private static final int STUN_HOLD_TICKS = 10;        // ~0.5 seconds right-click hold
    private static final int SLOT_SWITCH_DELAY = 3;        // Brief delay after switching slots
    private static final int MAX_MINE_TICKS = 80;          // Re-stun if mining takes too long
    private static final int MAX_ROTATE_TICKS = 30;        // Max ticks to rotate before forcing
    private static final double INTERACT_RANGE = 4.5;

    // Human-like timing randomization
    private int stunHoldTarget = STUN_HOLD_TICKS;

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

    public boolean isRunning() { return running; }
    public State getState() { return state; }

    public void tick(MinecraftClient client) {
        if (!running || client.player == null || client.world == null) return;
        tickCounter++;

        switch (state) {
            case SCANNING -> tickScanning(client);
            case PATHFINDING -> tickPathfinding(client);
            case ROTATING_TO_TAIL -> tickRotatingToTail(client);
            case STUNNING -> tickStunning(client);
            case SWITCHING_TO_MINE -> tickSwitchingToMine(client);
            case MINING -> tickMining(client);
            case SWITCHING_TO_STUN -> tickSwitchingToStun(client);
            case IDLE -> { }
        }
    }

    // ========================
    // SCANNING
    // ========================
    private void tickScanning(MinecraftClient client) {
        if (scanCooldown > 0) { scanCooldown--; return; }

        Optional<SnakeDetector.Snake> nearest = SnakeDetector.findNearestSnake(client);
        if (nearest.isPresent()) {
            targetSnake = nearest.get();
            currentTail = targetSnake.getTail();

            // If already in range, skip pathfinding
            Vec3d playerPos = PosHelper.getPos(client.player);
            if (playerPos.distanceTo(Vec3d.ofCenter(currentTail)) <= INTERACT_RANGE) {
                state = State.ROTATING_TO_TAIL;
                rotateTicks = 0;
            } else {
                state = State.PATHFINDING;
                navigator.setTarget(Vec3d.ofCenter(currentTail));
            }
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Found snake! (" + targetSnake.length() + " blocks)");
        } else {
            scanCooldown = SCAN_INTERVAL;
        }
    }

    // ========================
    // PATHFINDING
    // ========================
    private void tickPathfinding(MinecraftClient client) {
        if (targetSnake == null || !isSnakeStillValid(client)) {
            resetAndRescan("Snake lost");
            return;
        }

        // Re-trace to get updated tail position (snake may move)
        SnakeDetector.Snake updated = retraceSnake(client);
        if (updated != null) {
            targetSnake = updated;
            currentTail = targetSnake.getTail();
            navigator.setTarget(Vec3d.ofCenter(currentTail));
        }

        boolean arrived = navigator.tick(client);
        Vec3d playerPos = PosHelper.getPos(client.player);
        double distToTail = playerPos.distanceTo(Vec3d.ofCenter(currentTail));

        if (arrived || distToTail <= INTERACT_RANGE) {
            navigator.stop();
            controller.resetRotation();
            state = State.ROTATING_TO_TAIL;
            rotateTicks = 0;
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] In range! Preparing to stun...");
        }

        if (navigator.isStuck()) {
            resetAndRescan("Stuck! Finding new target");
        }
    }

    // ========================
    // ROTATING_TO_TAIL (smooth look at tail before stunning)
    // ========================
    private void tickRotatingToTail(MinecraftClient client) {
        if (targetSnake == null || !isSnakeStillValid(client)) {
            resetAndRescan("Snake lost");
            return;
        }

        rotateTicks++;

        // Smoothly rotate to look at the tail
        boolean aimed = controller.smoothLookAtBlock(client.player, currentTail);

        if (aimed || rotateTicks > MAX_ROTATE_TICKS) {
            // Switch to stun item (slot 2)
            controller.switchSlot(client, STUN_SLOT);
            state = State.STUNNING;
            stunTicks = 0;
            // Randomize stun duration slightly for human-like behavior
            stunHoldTarget = STUN_HOLD_TICKS + (int)(Math.random() * 4) - 2;
        }
    }

    // ========================
    // STUNNING (right-click hold with slot 2 on tail)
    // ========================
    private void tickStunning(MinecraftClient client) {
        if (targetSnake == null || !isSnakeStillValid(client)) {
            resetAndRescan("Snake lost during stun");
            return;
        }

        // Keep looking at tail smoothly
        controller.smoothLookAtBlock(client.player, currentTail);

        // Right-click the tail block each tick to hold the interaction
        controller.rightClickBlock(client, currentTail);
        stunTicks++;

        ChatUtils.sendActionBar("\u00a7b[SnakeFarm] Stunning... (" + stunTicks + "/" + stunHoldTarget + ")");

        if (stunTicks >= stunHoldTarget) {
            // Stun complete, switch to mining
            state = State.SWITCHING_TO_MINE;
            switchTicks = 0;
        }
    }

    // ========================
    // SWITCHING_TO_MINE (brief delay after slot switch)
    // ========================
    private void tickSwitchingToMine(MinecraftClient client) {
        if (switchTicks == 0) {
            controller.switchSlot(client, MINE_SLOT);
        }
        switchTicks++;

        // Keep aiming at tail during switch
        controller.smoothLookAtBlock(client.player, currentTail);

        // Small human-like delay
        int delay = SLOT_SWITCH_DELAY + (int)(Math.random() * 2);
        if (switchTicks >= delay) {
            state = State.MINING;
            mineTicks = 0;
        }
    }

    // ========================
    // MINING (left-click hold on tail block with slot 1)
    // ========================
    private void tickMining(MinecraftClient client) {
        if (targetSnake == null) {
            controller.stopMining(client);
            snakeCleared();
            return;
        }

        if (currentTail == null) {
            controller.stopMining(client);
            state = State.SCANNING;
            return;
        }

        mineTicks++;

        // Check if block already broken
        if (client.world.getBlockState(currentTail).isAir()) {
            blocksMined++;
            controller.stopMining(client);
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Mined! (" + blocksMined + " blocks total)");

            // Re-trace to find next tail
            targetSnake = retraceSnake(client);
            if (targetSnake == null) {
                snakeCleared();
                return;
            }

            currentTail = targetSnake.getTail();
            controller.resetRotation();

            // Switch back to stun item for next block
            state = State.SWITCHING_TO_STUN;
            switchTicks = 0;
            return;
        }

        // Keep smoothly aiming at the tail while mining
        controller.smoothLookAtBlock(client.player, currentTail);

        // Mine the block
        boolean broken = controller.mineBlock(client, currentTail);
        if (broken) {
            blocksMined++;
            ChatUtils.sendActionBar("\u00a7a[SnakeFarm] Mined! (" + blocksMined + " blocks total)");

            targetSnake = retraceSnake(client);
            if (targetSnake == null) {
                snakeCleared();
                return;
            }

            currentTail = targetSnake.getTail();
            controller.resetRotation();
            state = State.SWITCHING_TO_STUN;
            switchTicks = 0;
        }

        // Re-stun if mining takes too long
        if (mineTicks > MAX_MINE_TICKS) {
            controller.stopMining(client);
            mineTicks = 0;
            controller.resetRotation();
            state = State.ROTATING_TO_TAIL;
            rotateTicks = 0;
            ChatUtils.sendActionBar("\u00a7e[SnakeFarm] Stun wore off. Re-stunning...");
        }
    }

    // ========================
    // SWITCHING_TO_STUN (switch back to slot 2 for next stun)
    // ========================
    private void tickSwitchingToStun(MinecraftClient client) {
        if (switchTicks == 0) {
            controller.switchSlot(client, STUN_SLOT);
        }
        switchTicks++;

        // Small delay + start rotating to new tail
        controller.smoothLookAtBlock(client.player, currentTail);

        int delay = SLOT_SWITCH_DELAY + (int)(Math.random() * 2);
        if (switchTicks >= delay) {
            state = State.ROTATING_TO_TAIL;
            rotateTicks = 0;
        }
    }

    // ========================
    // Helpers
    // ========================

    private boolean isSnakeStillValid(MinecraftClient client) {
        if (targetSnake == null) return false;
        // Check if head (lapis) still exists
        return client.world.getBlockState(targetSnake.head).isOf(Blocks.LAPIS_BLOCK);
    }

    private SnakeDetector.Snake retraceSnake(MinecraftClient client) {
        if (targetSnake == null) return null;
        // Check if head still there
        if (!client.world.getBlockState(targetSnake.head).isOf(Blocks.LAPIS_BLOCK)) {
            return null;
        }
        // Re-scan from scratch near the head
        Vec3d headPos = Vec3d.ofCenter(targetSnake.head);
        return SnakeDetector.findSnakes(client).stream()
                .filter(s -> Vec3d.ofCenter(s.head).distanceTo(headPos) < 2.0)
                .findFirst()
                .orElse(null);
    }

    private void resetAndRescan(String reason) {
        ChatUtils.sendActionBar("\u00a7e[SnakeFarm] " + reason + ". Rescanning...");
        navigator.stop();
        MinecraftClient client = MinecraftClient.getInstance();
        controller.stopMining(client);
        controller.resetRotation();
        targetSnake = null;
        currentTail = null;
        state = State.SCANNING;
        scanCooldown = 10;
    }

    private void snakeCleared() {
        snakesKilled++;
        ChatUtils.send("\u00a7a[SnakeFarm] Snake cleared! (" + snakesKilled + " snakes, " + blocksMined + " blocks)");
        targetSnake = null;
        currentTail = null;
        mineTicks = 0;
        controller.resetRotation();
        state = State.SCANNING;
        scanCooldown = 5 + (int)(Math.random() * 10); // Random pause before next snake
    }

    private void resetState() {
        targetSnake = null;
        currentTail = null;
        tickCounter = 0;
        scanCooldown = 0;
        stunTicks = 0;
        switchTicks = 0;
        mineTicks = 0;
        rotateTicks = 0;
        controller.resetRotation();
    }

    public void stop() {
        running = false;
        state = State.IDLE;
        navigator.stop();
        MinecraftClient client = MinecraftClient.getInstance();
        controller.stopMining(client);
        controller.resetRotation();
        resetState();
    }

    // ========================
    // HUD
    // ========================
    public void renderHud(DrawContext context) {
        if (!running) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 4;
        int y = 4;
        int color = 0xFFFFFF;

        context.fill(x - 2, y - 2, x + 160, y + 62, 0x80000000);

        context.drawText(client.textRenderer, "\u00a7b\u00a7l[SnakeFarm]", x, y, color, true);
        y += 12;

        String stateColor = switch (state) {
            case SCANNING -> "\u00a7e";
            case PATHFINDING -> "\u00a76";
            case ROTATING_TO_TAIL, SWITCHING_TO_STUN, SWITCHING_TO_MINE -> "\u00a7d";
            case STUNNING -> "\u00a7c";
            case MINING -> "\u00a7a";
            default -> "\u00a7f";
        };
        context.drawText(client.textRenderer, stateColor + state.display, x, y, color, true);
        y += 12;

        if (targetSnake != null) {
            context.drawText(client.textRenderer, "\u00a77Snake: \u00a7f" + targetSnake.length() + " blocks left", x, y, color, true);
            y += 10;
        }

        context.drawText(client.textRenderer, "\u00a77Snakes: \u00a7f" + snakesKilled, x, y, color, true);
        y += 10;
        context.drawText(client.textRenderer, "\u00a77Blocks: \u00a7f" + blocksMined, x, y, color, true);
    }
}
