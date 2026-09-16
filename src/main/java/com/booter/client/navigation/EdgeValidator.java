package com.booter.client.navigation;

import com.booter.client.config.ConfigManager;
import com.booter.client.pathfinder.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class EdgeValidator {
    private final ConfigManager config;

    public EdgeValidator(ConfigManager config) {
        this.config = config;
    }

    public List<BlockPos> validate(Level level, NavigationWaypoint from, NavigationWaypoint to, Pathfinder localPathfinder) {
        if (level == null || from == null || to == null || localPathfinder == null) {
            return null;
        }
        if (tooFarForLocal(from, to)) {
            return null;
        }
        if (lineContainsDanger(level, from.blockPos(), to.blockPos())) {
            return null;
        }
        return localPathfinder.findLocalPath(level, from.blockPos(), to.blockPos());
    }

    private boolean tooFarForLocal(NavigationWaypoint from, NavigationWaypoint to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal > config.settings.hierarchyMaxLocalDistance;
    }

    private boolean lineContainsDanger(Level level, BlockPos from, BlockPos to) {
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(from.distSqr(to))));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (dangerous(level.getBlockState(new BlockPos(x, y, z)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean dangerous(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }
}
