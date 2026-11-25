package com.unstablestory.story;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Picks where scenes and characters go, so nobody starts on top of a spike,
 * inside a wall or over a drop.
 */
final class ScenePlacer {
    private ScenePlacer() {}

    /** Ground a player or bot can stand on: solid below, two free blocks, no water or lava. */
    static boolean standable(Level level, BlockPos feet) {
        BlockPos below = feet.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && level.getFluidState(below).isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.above()).isEmpty();
    }

    /** The open ground surface at x, z (feet position), or null if that column isn't loaded or usable. */
    static BlockPos surface(Level level, int x, int z) {
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.hasChunkAt(column)) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinY()) {
            return null;
        }
        BlockPos feet = new BlockPos(x, y, z);
        return standable(level, feet) ? feet : null;
    }

    /**
     * Find an open, fairly flat area around 'near', 'size' blocks in each direction,
     * whose ground stays within 'maxStep' blocks of height. Returns its centre, or
     * the flattest area found if none is flat enough, or null if nothing is usable.
     */
    static BlockPos findArena(Level level, BlockPos near, int searchRadius, int size, int maxStep) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        int step = Math.max(4, size / 3);
        for (int r = 0; r <= searchRadius; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue; // only the ring at this distance
                    }
                    BlockPos centre = surface(level, near.getX() + dx, near.getZ() + dz);
                    if (centre == null) {
                        continue;
                    }
                    int score = roughness(level, centre, size, step);
                    if (score < 0) {
                        continue;
                    }
                    if (score <= maxStep) {
                        return centre; // closest area that is flat enough
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = centre;
                    }
                }
            }
        }
        return best;
    }

    /** Height spread of the ground over the area, or -1 if part of it isn't standable. */
    private static int roughness(Level level, BlockPos centre, int size, int step) {
        int min = centre.getY();
        int max = centre.getY();
        for (int dx = -size; dx <= size; dx += step) {
            for (int dz = -size; dz <= size; dz += step) {
                BlockPos p = surface(level, centre.getX() + dx, centre.getZ() + dz);
                if (p == null) {
                    return -1;
                }
                min = Math.min(min, p.getY());
                max = Math.max(max, p.getY());
            }
        }
        return max - min;
    }

    /**
     * A safe spot for a character near x, z, at about the height of the scene
     * (so they stand between the spikes, not on top of them). Searches outwards
     * up to 8 blocks. Falls back to the ground surface.
     */
    static BlockPos safeSpot(Level level, int x, int z, int nearY) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos column = new BlockPos(x + dx, nearY, z + dz);
                    if (!level.hasChunkAt(column)) {
                        continue;
                    }
                    // Look a little above and below the scene height, closest first.
                    for (int dy = 0; dy <= 12; dy++) {
                        BlockPos up = column.above(dy);
                        if (standable(level, up)) {
                            return up;
                        }
                        BlockPos down = column.below(dy);
                        if (dy > 0 && standable(level, down)) {
                            return down;
                        }
                    }
                }
            }
        }
        BlockPos top = surface(level, x, z);
        return top != null ? top : new BlockPos(x, nearY, z);
    }
}
