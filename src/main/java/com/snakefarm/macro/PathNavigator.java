package com.snakefarm.macro;

import com.snakefarm.SnakeFarmMod;
import com.snakefarm.util.PosHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PathNavigator {
    private static final double ARRIVAL_DISTANCE = 3.5;
    private static final double MINING_DISTANCE = 4.5;

    private Vec3d lastPosition = null;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 40;
    private static final double STUCK_MOVE_THRESHOLD = 0.1;

    private int jumpCooldown = 0;

    private Vec3d targetPos = null;
    private Entity targetEntity = null;

    public void setTarget(Entity entity) {
        this.targetEntity = entity;
        this.targetPos = PosHelper.getPos(entity);
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

    public boolean tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        if (targetEntity != null) {
            if (!targetEntity.isAlive()) {
                clearTarget();
                return false;
            }
            targetPos = PosHelper.getPos(targetEntity);
        }

        if (targetPos == null) return false;

        Vec3d playerPos = PosHelper.getPos(player);
        double distance = playerPos.distanceTo(targetPos);

        if (distance <= ARRIVAL_DISTANCE) {
            stopMovement(player);
            return true;
        }

        lookAt(player, targetPos);
        moveTowards(client, player, targetPos);

        if (checkStuck(playerPos)) {
            handleStuck(player);
        }

        if (jumpCooldown > 0) jumpCooldown--;

        return false;
    }

    public boolean isInMiningRange(MinecraftClient client, Vec3d target) {
        if (client.player == null) return false;
        return PosHelper.getPos(client.player).distanceTo(target) <= MINING_DISTANCE;
    }

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d playerEyes = player.getEyePos();
        double dx = target.x - playerEyes.x;
        double dy = target.y - playerEyes.y;
        double dz = target.z - playerEyes.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(MathHelper.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        float smoothFactor = 0.4f;
        player.setYaw(lerpAngle(player.getYaw(), targetYaw, smoothFactor));
        player.setPitch(lerpAngle(player.getPitch(), targetPitch, smoothFactor));
    }

    private void moveTowards(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(true);

        if (shouldJump(client, player, target)) {
            if (jumpCooldown <= 0) {
                player.jump();
                jumpCooldown = 10;
            }
        }
    }

    private boolean shouldJump(MinecraftClient client, ClientPlayerEntity player, Vec3d target) {
        if (!player.isOnGround()) return false;

        if (target.y - player.getY() > 0.5) return true;

        Vec3d lookDir = Vec3d.fromPolar(0, player.getYaw()).normalize();
        BlockPos ahead = player.getBlockPos().add(
                (int) Math.round(lookDir.x),
                0,
                (int) Math.round(lookDir.z)
        );

        if (client.world != null && !client.world.getBlockState(ahead).isAir()) {
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
        if (player.isOnGround()) {
            player.jump();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.leftKey.setPressed(stuckTicks % 40 < 20);
        client.options.rightKey.setPressed(stuckTicks % 40 >= 20);

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
