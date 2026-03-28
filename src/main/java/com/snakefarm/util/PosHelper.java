package com.snakefarm.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Helper to get entity positions. In 1.21.10 yarn mappings, Entity.getPos()
 * was removed. We use getX()/getY()/getZ() instead.
 */
public class PosHelper {
    public static Vec3d getPos(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }
}
