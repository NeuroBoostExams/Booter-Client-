package com.booter.client.pathfinder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared low-oxygen fallback for water modules. "3 bubbles left" is roughly
 * 90 air ticks: vanilla tracks 300 air ticks across 10 bubbles.
 */
public final class CoralRefillHelper {
    public static final int LOW_AIR = 90;
    public static final int SAFE_AIR = 180;
    private static final int SCAN_RADIUS = 32;
    private static final int VERTICAL_RADIUS = 16;

    private CoralRefillHelper() {
    }

    public static boolean needsRefill(LocalPlayer player) {
        return player.isInWater() && player.getAirSupply() <= LOW_AIR;
    }

    public static boolean shouldKeepRefilling(LocalPlayer player, boolean active) {
        return active && player.isInWater() && player.getAirSupply() < SAFE_AIR;
    }

    public static BlockPos findNearest(ClientLevel level, LocalPlayer player) {
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        int rSq = SCAN_RADIUS * SCAN_RADIUS;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                if (dx * dx + dz * dz > rSq) {
                    continue;
                }
                for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
                    BlockPos coral = base.offset(dx, dy, dz);
                    if (!isCoral(level.getBlockState(coral))) {
                        continue;
                    }
                    BlockPos refuge = feetTargetForHeadInCoral(level, coral);
                    if (refuge == null) {
                        continue;
                    }
                    double sq = distanceSq(player, refuge);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = refuge.immutable();
                    }
                }
            }
        }
        return best;
    }

    public static boolean reached(LocalPlayer player, BlockPos target) {
        if (target == null) {
            return false;
        }
        BlockPos head = headBlockForFeetTarget(target);
        double dx = head.getX() + 0.5 - player.getX();
        double dy = head.getY() + 0.5 - player.getEyeY();
        double dz = head.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz <= 0.9 * 0.9;
    }

    private static BlockPos feetTargetForHeadInCoral(ClientLevel level, BlockPos coral) {
        BlockPos feet = feetForHeadBlock(coral);
        if (isSwimmable(level, feet)) {
            return feet;
        }
        BlockPos below = feet.below();
        if (isSwimmable(level, below)) {
            return below;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos sideHead = coral.relative(direction);
            if (!isCoral(level.getBlockState(sideHead)) && !isSwimmable(level, sideHead)) {
                continue;
            }
            BlockPos sideFeet = feetForHeadBlock(sideHead);
            if (isSwimmable(level, sideFeet)) {
                return sideFeet;
            }
        }
        return null;
    }

    private static boolean isCoral(BlockState state) {
        return state.is(BlockTags.CORALS)
                || state.is(BlockTags.CORAL_BLOCKS)
                || state.is(BlockTags.CORAL_PLANTS)
                || state.is(BlockTags.WALL_CORALS);
    }

    private static boolean isSwimmable(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static BlockPos feetForHeadBlock(BlockPos head) {
        return head.below();
    }

    private static BlockPos headBlockForFeetTarget(BlockPos feet) {
        return feet.above();
    }

    private static double distanceSq(LocalPlayer player, BlockPos pos) {
        BlockPos head = headBlockForFeetTarget(pos);
        double dx = head.getX() + 0.5 - player.getX();
        double dy = head.getY() + 0.5 - player.getEyeY();
        double dz = head.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
