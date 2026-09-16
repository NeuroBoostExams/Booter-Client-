package com.booter.client.navigation;

import com.booter.client.config.ConfigManager;
import com.booter.client.pathdebug.PathDebugManager;
import com.booter.client.pathfinder.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class HierarchicalPathfinder {
    private final NavigationWaypointManager graph;
    private final GlobalPathfinder global;
    private final PathCache cache;
    private final EdgeValidator validator;
    private final ConfigManager config;

    public HierarchicalPathfinder(ConfigManager config, NavigationWaypointManager graph, GlobalPathfinder global, PathCache cache) {
        this.config = config;
        this.graph = graph;
        this.global = global;
        this.cache = cache;
        this.validator = new EdgeValidator(config);
    }

    public NavigationPath findPath(Level level, BlockPos start, BlockPos goal, Pathfinder localPathfinder) {
        ConfigManager.Settings settings = config.settings;
        graph.status().status = "planning";
        graph.status().lastFailure = "";
        if (level == null || start == null || goal == null || localPathfinder == null) {
            fail("Invalid navigation request");
            return NavigationPath.failed("Invalid navigation request");
        }
        if (!graph.enabled() || !graph.hasGraph() || start.distSqr(goal) < settings.hierarchyMinDistance * settings.hierarchyMinDistance) {
            return NavigationPath.failed("Hierarchy disabled or not useful");
        }

        Vec3 startVec = Vec3.atCenterOf(start);
        Vec3 goalVec = Vec3.atCenterOf(goal);
        NavigationWaypoint startWaypoint = graph.findNearestWaypoint(startVec);
        NavigationWaypoint targetWaypoint = graph.findNearestWaypoint(goalVec);
        if (startWaypoint == null || targetWaypoint == null) {
            fail("No nearby graph waypoint");
            return NavigationPath.failed("No nearby graph waypoint");
        }

        List<NavigationWaypoint> route = cache.get(startWaypoint.id, targetWaypoint.id,
                graph.version(), settings.hierarchyCacheLifetimeSeconds * 1000L);
        if (route == null || route.isEmpty() || !cachedRouteUsable(route)) {
            route = global.findRoute(startVec, goalVec);
            if (route.isEmpty()) {
                fail(graph.status().lastFailure.isBlank() ? "No global route" : graph.status().lastFailure);
                return NavigationPath.failed(graph.status().lastFailure);
            }
            cache.put(startWaypoint.id, targetWaypoint.id, graph.version(), route);
        }

        ArrayList<BlockPos> full = new ArrayList<>();
        appendSegment(full, localPathfinder.findLocalPath(level, start, route.get(0).blockPos()));
        if (full.size() < 2 && start.distSqr(route.get(0).blockPos()) > 9.0) {
            fail("No local route to first waypoint");
            return NavigationPath.failed("No local route to first waypoint");
        }

        for (int i = 0; i + 1 < route.size(); i++) {
            NavigationWaypoint from = route.get(i);
            NavigationWaypoint to = route.get(i + 1);
            NavigationEdge edge = findEdge(from.id, to.id);
            List<BlockPos> segment = validator.validate(level, from, to, localPathfinder);
            if (segment == null || segment.size() < 2) {
                graph.markEdgeInvalid(edge, "Local route blocked");
                graph.status().replans++;
                NavigationPath retry = findPath(level, start, goal, localPathfinder);
                if (!retry.found()) {
                    fail("Blocked edge " + from.id + " -> " + to.id);
                }
                return retry;
            }
            appendSegment(full, segment);
        }

        appendSegment(full, localPathfinder.findLocalPath(level, route.get(route.size() - 1).blockPos(), goal));
        if (full.size() < 2) {
            fail("No local route to target");
            return NavigationPath.failed("No local route to target");
        }

        graph.status().status = "navigating";
        graph.status().currentSegment = 0;
        graph.status().progress = 0.0;
        graph.status().distanceRemaining = pathDistance(full);
        PathDebugManager.get().setFinalPath(full);
        return NavigationPath.found(route, full, true);
    }

    private boolean cachedRouteUsable(List<NavigationWaypoint> route) {
        long now = System.currentTimeMillis();
        for (int i = 0; i + 1 < route.size(); i++) {
            NavigationEdge edge = findEdge(route.get(i).id, route.get(i + 1).id);
            if (edge == null || edge.isTemporarilyInvalid(now)) {
                return false;
            }
        }
        return true;
    }

    private NavigationEdge findEdge(String from, String to) {
        for (NavigationEdge edge : graph.edgesFrom(from)) {
            if (to.equals(edge.other(from))) {
                return edge;
            }
        }
        return null;
    }

    private static void appendSegment(ArrayList<BlockPos> out, List<BlockPos> segment) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        for (BlockPos pos : segment) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(pos)) {
                out.add(pos);
            }
        }
    }

    private static double pathDistance(List<BlockPos> path) {
        double total = 0.0;
        for (int i = 0; i + 1 < path.size(); i++) {
            total += Math.sqrt(path.get(i).distSqr(path.get(i + 1)));
        }
        return total;
    }

    private void fail(String reason) {
        graph.status().status = "failed";
        graph.status().lastFailure = reason;
    }
}
