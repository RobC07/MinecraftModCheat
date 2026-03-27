package com.snakefarm.macro;

import com.snakefarm.mixin.MinecraftClientAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Handles player actions: attacking entities, mining blocks, and looking at targets.
 */
public class PlayerController {
    private BlockPos currentMiningBlock = null;
    private int miningTicks = 0;
    private boolean isMining = false;

    /**
     * Attacks/stuns a snake entity by left-clicking it.
     */
    public void attackEntity(MinecraftClient client, Entity target) {
        if (client.player == null || client.interactionManager == null) return;

        // Look directly at the entity
        lookAtEntity(client.player, target);

        // Reset attack cooldown for instant hit
        ((MinecraftClientAccessor) client).setAttackCooldown(0);

        // Attack the entity
        client.interactionManager.attackEntity(client.player, target);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    /**
     * Starts or continues mining a block at the given position.
     * Returns true when the block is fully broken.
     */
    public boolean mineBlock(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.interactionManager == null || client.world == null) return false;

        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) {
            // Block already broken
            stopMining(client);
            return true;
        }

        // Look at the block
        lookAtBlock(client.player, pos);

        if (!pos.equals(currentMiningBlock)) {
            // Start mining new block
            stopMining(client);
            currentMiningBlock = pos;
            miningTicks = 0;
            isMining = true;

            // Send start-destroy packet
            Direction face = getClosestFace(client.player, pos);
            client.interactionManager.attackBlock(pos, face);
            client.player.swingHand(Hand.MAIN_HAND);
        } else {
            // Continue mining
            miningTicks++;
            Direction face = getClosestFace(client.player, pos);
            // Update block breaking progress
            if (client.interactionManager.updateBlockBreakingProgress(pos, face)) {
                client.player.swingHand(Hand.MAIN_HAND);
                stopMining(client);
                return true; // Block broken
            }
            client.player.swingHand(Hand.MAIN_HAND);
        }

        return false;
    }

    /**
     * Stops any ongoing mining operation.
     */
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
     * Rotates the player to look directly at an entity.
     */
    public void lookAtEntity(ClientPlayerEntity player, Entity target) {
        Vec3d targetPos = target.getPos().add(0, target.getHeight() / 2.0, 0);
        lookAtPos(player, targetPos);
    }

    /**
     * Rotates the player to look at a block position (center of block).
     */
    public void lookAtBlock(ClientPlayerEntity player, BlockPos pos) {
        Vec3d targetPos = Vec3d.ofCenter(pos);
        lookAtPos(player, targetPos);
    }

    /**
     * Smoothly rotates the player to look at a world position.
     */
    private void lookAtPos(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        // Fast snap for combat precision
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    /**
     * Gets the closest block face relative to the player.
     */
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
}
