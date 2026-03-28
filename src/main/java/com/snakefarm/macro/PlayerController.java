package com.snakefarm.macro;

import com.snakefarm.util.PosHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PlayerController {
    private BlockPos currentMiningBlock = null;
    private int miningTicks = 0;
    private boolean isMining = false;

    // Smooth rotation state
    private float currentYaw = Float.NaN;
    private float currentPitch = Float.NaN;
    private static final float ROTATION_SPEED = 0.15f; // Lower = smoother/slower

    /**
     * Switches to a hotbar slot (0-indexed).
     */
    public void switchSlot(MinecraftClient client, int slot) {
        if (client.player == null) return;
        client.player.getInventory().selectedSlot = slot;
    }

    /**
     * Right-clicks while looking at a block (for stunning with slot 2 item).
     */
    public void rightClickBlock(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.interactionManager == null) return;

        Direction face = getClosestFace(client.player, pos);
        Vec3d hitVec = Vec3d.ofCenter(pos).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * Starts or continues mining a block at the given position.
     * Returns true when the block is fully broken.
     */
    public boolean mineBlock(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.interactionManager == null || client.world == null) return false;

        if (client.world.getBlockState(pos).isAir()) {
            stopMining(client);
            return true;
        }

        if (!pos.equals(currentMiningBlock)) {
            stopMining(client);
            currentMiningBlock = pos;
            miningTicks = 0;
            isMining = true;

            Direction face = getClosestFace(client.player, pos);
            client.interactionManager.attackBlock(pos, face);
            client.player.swingHand(Hand.MAIN_HAND);
        } else {
            miningTicks++;
            Direction face = getClosestFace(client.player, pos);
            if (client.interactionManager.updateBlockBreakingProgress(pos, face)) {
                client.player.swingHand(Hand.MAIN_HAND);
                stopMining(client);
                return true;
            }
            client.player.swingHand(Hand.MAIN_HAND);
        }

        return false;
    }

    public void stopMining(MinecraftClient client) {
        if (isMining && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        currentMiningBlock = null;
        miningTicks = 0;
        isMining = false;
    }

    public boolean isMining() {
        return isMining;
    }

    /**
     * Smoothly rotates the player to look at a block.
     * Call this every tick for gradual rotation.
     * Returns true when rotation is close enough to target.
     */
    public boolean smoothLookAtBlock(ClientPlayerEntity player, BlockPos pos) {
        return smoothLookAtPos(player, Vec3d.ofCenter(pos));
    }

    /**
     * Smoothly rotates the player to look at a position.
     * Returns true when rotation is close enough (within 2 degrees).
     */
    public boolean smoothLookAtPos(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(MathHelper.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        // Initialize smooth rotation tracking
        if (Float.isNaN(currentYaw)) {
            currentYaw = player.getYaw();
            currentPitch = player.getPitch();
        }

        // Smooth lerp with slight randomization for human-like movement
        float speed = ROTATION_SPEED + (float) (Math.random() * 0.05 - 0.025);
        currentYaw = lerpAngle(currentYaw, targetYaw, speed);
        currentPitch = lerpAngle(currentPitch, targetPitch, speed);

        // Add tiny micro-jitter like a real mouse
        float jitterYaw = (float) (Math.random() * 0.3 - 0.15);
        float jitterPitch = (float) (Math.random() * 0.2 - 0.1);

        player.setYaw(currentYaw + jitterYaw);
        player.setPitch(currentPitch + jitterPitch);

        // Check if we're close enough
        float yawDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float pitchDiff = Math.abs(MathHelper.wrapDegrees(targetPitch - currentPitch));
        return yawDiff < 2.0f && pitchDiff < 2.0f;
    }

    /**
     * Instantly snaps look to a block (used as fallback).
     */
    public void lookAtBlock(ClientPlayerEntity player, BlockPos pos) {
        lookAtPos(player, Vec3d.ofCenter(pos));
    }

    private void lookAtPos(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        player.setYaw(yaw);
        player.setPitch(pitch);
        currentYaw = yaw;
        currentPitch = pitch;
    }

    public void resetRotation() {
        currentYaw = Float.NaN;
        currentPitch = Float.NaN;
    }

    private Direction getClosestFace(PlayerEntity player, BlockPos pos) {
        Vec3d playerPos = player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(pos);
        Vec3d diff = playerPos.subtract(blockCenter);

        double absX = Math.abs(diff.x);
        double absY = Math.abs(diff.y);
        double absZ = Math.abs(diff.z);

        if (absY >= absX && absY >= absZ) {
            return diff.y > 0 ? Direction.UP : Direction.DOWN;
        } else if (absX >= absZ) {
            return diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private float lerpAngle(float current, float target, float factor) {
        float diff = MathHelper.wrapDegrees(target - current);
        return current + diff * factor;
    }
}
