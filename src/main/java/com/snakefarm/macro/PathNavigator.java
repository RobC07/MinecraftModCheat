package com.snakefarm.macro;

import com.snakefarm.SnakeFarmMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Handles pathfinding and movement towards target entities/positions.
 *
 * Uses a simple direct-walk approach with jump detection and obstacle avoidance.
 * For the Living Cave environment, this works well since the terrain is relatively
 * flat cave floors with minor obstacles.
 */
public class PathNavigator {
    // How close we need to be to consider "arrived"
    private static final double ARRIVAL_DISTANCE = 3.5;
    private static final double MINING_DISTANCE = 4.5;

    // Anti-stuck detection
    private Vec3d lastPosition = null;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 40; // ~2 seconds
    private static final double STUCK_MOVE_THRESHOLD = 0.1;

    // Jump cooldown
    private int jumpCooldown = 0;

    private Vec3d targetPos = null;
    private Entity targetEntity = null;

    public void setTarget(Entity entity) {
        this.targetEntity = entity;
        this.targetPos = entity.getPos();
        resetStuckDetection();
    }

    public void setTarget(Vec3d pos) {
        this.targetEntity = null;
        this.targetPos = pos;
        resetStuckDetection();
    }

    public void clearTarget() {
        this.targetEntity = null;
        this.targetPos = null;
        resetStuckDetection();
    }

    public boolean hasTarget() {
        return targetPos != null || targetEntity != null;
    }

    /**
     * Main navigation tick. Moves the player towards the current target.
     * Returns true if we've arrived at the target.
     */
    public boolean tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        // Update target position if tracking an entity
        if (targetEntity != null) {
            if (!targetEntity.isAlive()) {
                clearTarget();
                return false;
            }
            targetPos = targetEntity.getPos();
        }

        if (targetPos == null) return false;

        Vec3d playerPos = player.getPos();
        double distance = playerPos.distanceTo(targetPos);

        // Check if arrived
        if (distance <= ARRIVAL_DISTANCE) {
            stopMovement(player);
            return true;
        }

        // Look at target
        lookAt(player, targetPos);

        // Move towards target
        moveTowards(client, player, targetPos);

        // Anti-stuck detection
        if (checkStuck(playerPos)) {
            handleStuck(player);
        }

        if (jumpCooldown > 0) jumpCooldown--;

        return false;
    }

    /**
     * Checks if the player is within mining range of a position.
     */
    public boolean isInMiningRange(MinecraftClient client, Vec3d target) {
        if (client.player == null) return false;
        return client.player.getPos().distanceTo(target) <= MINING_DISTANCE;
    }

    /**
     * Rotates the player to look at a target position smoothly.
     */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d playerEyes = player.getEyePos();
        double dx = target.x - playerEyes.x;
        double dy = target.y - playerEyes.y;
        double dz = target.z - playerEyes.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(MathHelper.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        // Smooth rotation (lerp)
        float smoothFactor = 0.4f;
        player.setYaw(lerpAngle(player.getYaw(), targetYaw, smoothFactor));
        player.setPitch(lerpAngle(player.getPitch(), targetPitch, smoothFactor));
    }

    /**
     * Simulates movement input towards the target.
     */
    private void moveTowards(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        // Calculate direction
        Vec3d playerPos = player.getPos();
        Vec3d direction = target.subtract(playerPos).normalize();

        // Set movement input (forward)
        // We use the options to simulate key presses
        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        // Sprint for faster movement
        client.options.sprintKey.setPressed(true);

        // Jump if we need to go up or there's an obstacle
        if (shouldJump(client, player, target)) {
            if (jumpCooldown <= 0) {
                player.jump();
                jumpCooldown = 10; // Half second cooldown
            }
        }
    }

    /**
     * Determines if the player should jump (obstacle ahead or target is above).
     */
    private boolean shouldJump(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        if (!player.isOnGround()) return false;

        // Jump if target is significantly above us
        if (target.y - player.getY() > 0.5) return true;

        // Jump if there's a block in front of us (obstacle)
        Vec3d lookDir = Vec3d.fromPolar(0, player.getYaw()).normalize();
        BlockPos ahead = player.getBlockPos().add(
                (int) Math.round(lookDir.x),
                0,
                (int) Math.round(lookDir.z)
        );

        if (client.world != null && !client.world.getBlockState(ahead).isAir()) {
            // Check if we can jump over it (block above the obstacle is air)
            BlockPos aboveAhead = ahead.up();
            if (client.world.getBlockState(aboveAhead).isAir()) {
                return true;
            }
        }

        return false;
    }

    private void stopMovement(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private boolean checkStuck(Vec3d currentPos) {
        if (lastPosition != null && currentPos.distanceTo(lastPosition) < STUCK_MOVE_THRESHOLD) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = currentPos;
        return stuckTicks >= STUCK_THRESHOLD;
    }

    private void handleStuck(ClientPlayerEntity player) {
        SnakeFarmMod.LOGGER.warn("[SnakeFarm] Player appears stuck, attempting to unstick...");
        // Jump and strafe to try to get unstuck
        if (player.isOnGround()) {
            player.jump();
        }
        // Briefly strafe
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.leftKey.setPressed(stuckTicks % 40 < 20);
        client.options.rightKey.setPressed(stuckTicks % 40 >= 20);

        // If stuck for too long, signal that we need a new target
        if (stuckTicks > STUCK_THRESHOLD * 3) {
            resetStuckDetection();
            clearTarget();
        }
    }

    public boolean isStuck() {
        return stuckTicks >= STUCK_THRESHOLD;
    }

    private void resetStuckDetection() {
        stuckTicks = 0;
        lastPosition = null;
    }

    private float lerpAngle(float current, float target, float factor) {
        float diff = MathHelper.wrapDegrees(target - current);
        return current + diff * factor;
    }

    public void stop() {
        clearTarget();
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }
}
