package twobeetwoteelol.utils;

import net.minecraft.util.math.BlockPos;
import twobeetwoteelol.model.ExclusionZone;

import java.util.List;

public final class Coordinates {
    public static boolean isExcludedZone(List<ExclusionZone> zones, BlockPos signPos) {
        for (ExclusionZone zone : zones) {
            if (zone.matches(signPos)) {
                return true;
            }
        }

        return false;
    }

    public static boolean inRadius(int x, int z, int radius, BlockPos pos) {
        long dx = pos.getX() - x;
        long dz = pos.getZ() - z;
        long distanceSq = dx * dx + dz * dz;
        long radiusSq = (long) radius * radius;

        return distanceSq <= radiusSq;
    }

    public static boolean isWithinSpawnRadius(
        boolean onlyWithinSpawn,
        String dimension,
        int x,
        int z,
        int overworldSpawnRadius,
        int netherSpawnRadius,
        int endSpawnRadius
    ) {
        if (!onlyWithinSpawn) {
            return true;
        }

        long dx = x;
        long dz = z;
        long distanceSq = dx * dx + dz * dz;
        long radius = getSpawnRadius(dimension, overworldSpawnRadius, netherSpawnRadius, endSpawnRadius);
        long radiusSq = radius * radius;
        return distanceSq <= radiusSq;
    }

    public static int getSpawnRadius(
        String dimension,
        int overworldSpawnRadius,
        int netherSpawnRadius,
        int endSpawnRadius
    ) {
        if ("minecraft:the_nether".equals(dimension)) {
            return netherSpawnRadius;
        }

        if ("minecraft:the_end".equals(dimension)) {
            return endSpawnRadius;
        }

        return overworldSpawnRadius;
    }
}
