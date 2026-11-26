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

    /** Where a border was found: the spot to stand on, and the yaw that faces across the border. */
    record Border(BlockPos feet, float yaw) {}

    static String biomeName(Level level, BlockPos pos) {
        return level.getBiome(pos).getRegisteredName();
    }

    /**
     * Find the closest spot on top of the terrain that is NOT in a biome whose name
     * contains 'word', right next to one that is (for example, on top of the normal
     * Far Lands, at the edge of the Spikelands). Every biome name seen is added to
     * 'seen', to help when the name guess is wrong.
     */
    static Border findBorder(Level level, BlockPos from, int searchRadius, String word, java.util.Set<String> seen) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int r = 0; r <= searchRadius; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                for (int dz = -r; dz <= r; dz += 4) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos top = surface(level, from.getX() + dx, from.getZ() + dz);
                    if (top == null) {
                        continue;
                    }
                    String here = biomeName(level, top);
                    seen.add(here);
                    if (here.contains(word)) {
                        continue;
                    }
                    for (int[] d : dirs) {
                        BlockPos probe = new BlockPos(top.getX() + d[0] * 6, top.getY(), top.getZ() + d[1] * 6);
                        if (!level.hasChunkAt(probe)) {
                            continue;
                        }
                        String there = biomeName(level, probe);
                        seen.add(there);
                        if (there.contains(word)) {
                            // Minecraft yaw: 0 faces +Z, 90 faces -X, -90 faces +X, 180 faces -Z.
                            float yaw = d[0] > 0 ? -90f : d[0] < 0 ? 90f : d[1] > 0 ? 0f : 180f;
                            return new Border(top, yaw);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Make sure the chunks around a spot exist (generating them if needed) before searching there. */
    static void loadArea(Level level, BlockPos centre, int radius) {
        int c0x = (centre.getX() - radius) >> 4, c1x = (centre.getX() + radius) >> 4;
        int c0z = (centre.getZ() - radius) >> 4, c1z = (centre.getZ() + radius) >> 4;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

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
