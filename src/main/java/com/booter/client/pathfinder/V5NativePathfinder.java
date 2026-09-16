package com.booter.client.pathfinder;

import com.chattriggers.ctjs.api.world.pathfinding.NativePathResult;
import com.chattriggers.ctjs.api.world.pathfinding.NativePathfinderBridge;
import com.chattriggers.ctjs.api.world.pathfinding.NativeVoxelFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin adapter for V5Loader's native pathfinder library. It snapshots loaded
 * chunks around the requested search into the native world cache, then converts
 * the returned flat xyz array into Booter Client's BlockPos path API.
 */
public final class V5NativePathfinder {
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final int CHUNK_MARGIN = 3;
    private static final double HEURISTIC_WEIGHT = 1.05;
    private static final double NON_PRIMARY_START_PENALTY = 250.0;
    private static final short AIR_FLAGS = (short) (NativeVoxelFlags.PASSABLE
            | NativeVoxelFlags.PASSABLE_FLY
            | NativeVoxelFlags.ETHER_PASSABLE
            | NativeVoxelFlags.ETHER_TELEPORT_CLEAR);

    private V5NativePathfinder() {
    }

    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos goal, int maxIterations) {
        return findPath(level, start, List.of(goal), maxIterations);
    }

    public static List<BlockPos> findPath(Level level, BlockPos start, List<BlockPos> goals, int maxIterations) {
        if (level == null || start == null || goals == null || goals.isEmpty() || !NativePathfinderBridge.isAvailable()) {
            return null;
        }
        try {
            uploadWorld(level, start, goals);
            int[] starts = {start.getX(), start.getY(), start.getZ()};
            int[] ends = flatten(goals);
            NativePathResult result = NativePathfinderBridge.findPath(new NativePathfinderBridge.NativePathSearchRequest(
                    starts, ends, false, Math.max(1_000, maxIterations), HEURISTIC_WEIGHT,
                    NON_PRIMARY_START_PENALTY, 0, new int[0], new double[0]));
            if (result == null || result.keyPath == null || result.keyPath.length < 6) {
                return null;
            }
            return toPath(result.keyPath.length > 0 ? result.keyPath : result.path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void uploadWorld(Level level, BlockPos start, List<BlockPos> goals) {
        NativePathfinderBridge.setWorld("booter_runtime", MIN_Y, MAX_Y);
        int minChunkX = start.getX() >> 4;
        int maxChunkX = minChunkX;
        int minChunkZ = start.getZ() >> 4;
        int maxChunkZ = minChunkZ;
        for (BlockPos goal : goals) {
            minChunkX = Math.min(minChunkX, goal.getX() >> 4);
            maxChunkX = Math.max(maxChunkX, goal.getX() >> 4);
            minChunkZ = Math.min(minChunkZ, goal.getZ() >> 4);
            maxChunkZ = Math.max(maxChunkZ, goal.getZ() >> 4);
        }
        minChunkX -= CHUNK_MARGIN;
        maxChunkX += CHUNK_MARGIN;
        minChunkZ -= CHUNK_MARGIN;
        maxChunkZ += CHUNK_MARGIN;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (level.hasChunk(cx, cz)) {
                    uploadChunk(level, cx, cz);
                }
            }
        }
    }

    private static void uploadChunk(Level level, int chunkX, int chunkZ) {
        int sectionCount = (MAX_Y - MIN_Y + 15) >> 4;
        long sectionMask = sectionCount >= 64 ? -1L : (1L << sectionCount) - 1L;
        short[] flags = new short[sectionCount * 4096];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int offset = 0;
        for (int section = 0; section < sectionCount; section++) {
            int baseY = MIN_Y + (section << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldX = (chunkX << 4) + x;
                        int worldY = baseY + y;
                        int worldZ = (chunkZ << 4) + z;
                        flags[offset++] = flagsFor(level, pos.set(worldX, worldY, worldZ));
                    }
                }
            }
        }
        NativePathfinderBridge.upsertChunk(chunkX, chunkZ, MIN_Y, MAX_Y, sectionMask, flags);
    }

    private static short flagsFor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return AIR_FLAGS;
        }
        int flags = 0;
        Block block = state.getBlock();
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        if (!state.getFluidState().isEmpty()) {
            flags |= NativeVoxelFlags.FLUID;
        }
        if (block instanceof CarpetBlock) {
            return (short) (flags | NativeVoxelFlags.PASSABLE | NativeVoxelFlags.PASSABLE_FLY | NativeVoxelFlags.CARPET_LIKE);
        }
        if (block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof WallBlock) {
            flags |= NativeVoxelFlags.SOLID | NativeVoxelFlags.BLOCKING_WALL | NativeVoxelFlags.FENCE_LIKE;
        } else if (block instanceof SlabBlock) {
            flags |= NativeVoxelFlags.SOLID;
            SlabType type = state.getValue(SlabBlock.TYPE);
            if (type == SlabType.BOTTOM) flags |= NativeVoxelFlags.SLAB_BOTTOM;
            if (type == SlabType.TOP) flags |= NativeVoxelFlags.SLAB_TOP | NativeVoxelFlags.BLOCKING_WALL;
            if (type == SlabType.DOUBLE) flags |= NativeVoxelFlags.BLOCKING_WALL;
        } else if (block instanceof StairBlock) {
            flags |= NativeVoxelFlags.SOLID;
            if (state.getValue(StairBlock.HALF) == Half.BOTTOM) {
                flags |= NativeVoxelFlags.STAIRS_BOTTOM;
            }
        } else if (shape.isEmpty() || block == Blocks.WATER) {
            flags |= NativeVoxelFlags.PASSABLE | NativeVoxelFlags.PASSABLE_FLY;
        } else {
            flags |= NativeVoxelFlags.SOLID;
            if (shape.bounds().maxY - shape.bounds().minY >= 0.5
                    && !(block instanceof LadderBlock) && !(block instanceof VineBlock)) {
                flags |= NativeVoxelFlags.BLOCKING_WALL;
            }
        }
        if ((flags & NativeVoxelFlags.PASSABLE) != 0) {
            flags |= NativeVoxelFlags.PASSABLE_FLY | NativeVoxelFlags.ETHER_PASSABLE | NativeVoxelFlags.ETHER_TELEPORT_CLEAR;
        }
        return (short) flags;
    }

    private static int[] flatten(List<BlockPos> goals) {
        int[] out = new int[goals.size() * 3];
        for (int i = 0; i < goals.size(); i++) {
            BlockPos p = goals.get(i);
            int base = i * 3;
            out[base] = p.getX();
            out[base + 1] = p.getY();
            out[base + 2] = p.getZ();
        }
        return out;
    }

    private static List<BlockPos> toPath(int[] flat) {
        List<BlockPos> out = new ArrayList<>(flat.length / 3);
        for (int i = 0; i + 2 < flat.length; i += 3) {
            BlockPos pos = new BlockPos(flat[i], flat[i + 1], flat[i + 2]);
            if (out.isEmpty() || !out.get(out.size() - 1).equals(pos)) {
                out.add(pos);
            }
        }
        return out.size() >= 2 ? out : null;
    }
}
