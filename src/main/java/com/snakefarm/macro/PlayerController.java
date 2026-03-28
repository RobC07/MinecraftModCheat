package com.snakefarm.macro;

import com.snakefarm.mixin.MinecraftClientAccessor;
import com.snakefarm.util.PosHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PlayerController {
    private BlockPos currentMiningBlock = null;
    private int miningTicks = 0;
    private boolean isMining = false;

    public void attackEntity(MinecraftClient client, Entity target) {
        if (client.player == null || client.interactionManager == null) return;

        lookAtEntity(client.player, target);
        ((MinecraftClientAccessor) client).setAttackCooldown(0);
        client.interactionManager.attackEntity(client.player, target);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    public boolean mineBlock(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.interactionManager == null || client.world == null) return false;

        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) {
            stopMining(client);
            return true;
        }

        lookAtBlock(client.player, pos);

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

    public void lookAtEntity(ClientPlayerEntity player, Entity target) {
        Vec3d targetPos = PosHelper.getPos(target).add(0, target.getHeight() / 2.0, 0);
        lookAtPos(player, targetPos);
    }

    public void lookAtBlock(ClientPlayerEntity player, BlockPos pos) {
        Vec3d targetPos = Vec3d.ofCenter(pos);
        lookAtPos(player, targetPos);
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
}
