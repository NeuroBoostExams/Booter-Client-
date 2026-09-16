package com.booter.client.pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.navigation.NavigationPath;
import com.booter.client.navigation.NavigationWaypointManager;
import com.booter.client.pathcore.Node;
import com.booter.client.pathcore.movement.PathSmoother;
import com.booter.client.pathcore.movement.WalkabilityChecker;
import com.booter.client.pathcore.pathfinder.AStarPathfinder;
import com.booter.client.pathcore.pathing.NeighborStrategies;
import com.booter.client.pathcore.pathing.configuration.PathfinderConfiguration;
import com.booter.client.pathcore.pathing.context.EnvironmentContext;
import com.booter.client.pathcore.pathing.heuristic.HeuristicWeights;
import com.booter.client.pathcore.pathing.processing.impl.MinecraftPathProcessor;
import com.booter.client.pathcore.pathing.result.PathState;
import com.booter.client.pathcore.provider.impl.MinecraftNavigationProvider;
import com.booter.client.pathcore.wrapper.PathPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter from imported pathfinding engine to Booter Client's existing
 * List<BlockPos> path API.
 *
 * <p>Contains imported GPL-3.0 pathfinding code. Keep the project license
 * notice in sync when redistributing source or binaries.</p>
 */
public final class Pathfinder {
    private final int maxIterations;
    private final int maxLength;
    private final int maxJumpHeight;

    public Pathfinder(int maxIterations, int maxRadius, boolean allowWater) {
        this(maxIterations, Math.max(32, maxRadius * 4), 3);
    }

    public Pathfinder(int maxIterations, int maxLength, int maxJumpHeight) {
        this.maxIterations = Math.max(128, maxIterations);
        this.maxLength = Math.max(16, maxLength);
        this.maxJumpHeight = Math.max(1, maxJumpHeight);
    }

    public List<BlockPos> findPath(Level level, BlockPos start, BlockPos goal) {
        if (level == null || start == null || goal == null) {
            return null;
        }
        try {
            NavigationWaypointManager navigation = BooterClient.navigation();
            if (navigation != null && navigation.enabled() && navigation.hasGraph()) {
                NavigationPath hierarchical = navigation.navigateTo(level, start, goal, this);
                if (hierarchical.found()) {
                    return hierarchical.localNodes();
                }
            }
        } catch (RuntimeException e) {
            BooterClient.LOGGER.warn("Hierarchical pathfinding failed, falling back to local A*", e);
        }
        return findLocalPath(level, start, goal);
    }

    public List<BlockPos> findLocalPath(Level level, BlockPos start, BlockPos goal) {
        if (level == null || start == null || goal == null) {
            return null;
        }
        List<BlockPos> nativePath = V5NativePathfinder.findPath(level, start, goal, maxIterations);
        if (nativePath != null) {
            return nativePath;
        }

        WalkabilityChecker checker = new WalkabilityChecker(level);
        PathfinderConfiguration configuration = PathfinderConfiguration.builder()
                .maxIterations(maxIterations)
                .maxLength(maxLength)
                .async(false)
                .fallback(false)
                .provider(new MinecraftNavigationProvider(checker))
                .processors(List.of(new MinecraftPathProcessor(checker, maxJumpHeight)))
                .neighborStrategy(NeighborStrategies.horizontalDiagonalAndVertical(maxJumpHeight))
                .heuristicWeights(HeuristicWeights.DEFAULT_WEIGHTS)
                .build();

        AStarPathfinder pathfinder = new AStarPathfinder(configuration);
        var result = pathfinder.findPath(toPosition(start), toPosition(goal), new EnvironmentContext() {})
                .toCompletableFuture()
                .join();
        if (result.getPathState() != PathState.FOUND || result.getPath().collect().isEmpty()) {
            return null;
        }

        List<Node> raw = new ArrayList<>(result.getPath().length());
        for (PathPosition position : result.getPath().collect()) {
            raw.add(new Node(position));
        }
        List<Node> smoothed = PathSmoother.smooth(raw, checker);
        List<BlockPos> out = new ArrayList<>(smoothed.size());
        for (Node node : smoothed) {
            BlockPos pos = new BlockPos(node.position.flooredX(), node.position.flooredY(), node.position.flooredZ());
            if (out.isEmpty() || !out.get(out.size() - 1).equals(pos)) {
                out.add(pos);
            }
        }
        return out.size() >= 2 ? out : null;
    }

    public List<BlockPos> findPath(Level level, BlockPos start, List<BlockPos> goals) {
        if (level == null || start == null || goals == null || goals.isEmpty()) {
            return null;
        }
        List<BlockPos> nativePath = V5NativePathfinder.findPath(level, start, goals, maxIterations);
        if (nativePath != null) {
            return nativePath;
        }
        List<BlockPos> best = null;
        for (BlockPos goal : goals) {
            List<BlockPos> path = findLocalPath(level, start, goal);
            if (path != null && (best == null || path.size() < best.size())) {
                best = path;
            }
        }
        return best;
    }

    private static PathPosition toPosition(BlockPos pos) {
        return new PathPosition(pos.getX(), pos.getY(), pos.getZ());
    }
}
