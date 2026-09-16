package com.booter.client.navigation;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class GlobalPathfinder {
    private final NavigationWaypointManager graph;

    public GlobalPathfinder(NavigationWaypointManager graph) {
        this.graph = graph;
    }

    public List<NavigationWaypoint> findRoute(Vec3 startPos, Vec3 targetPos) {
        NavigationWaypoint start = graph.findNearestWaypoint(startPos);
        NavigationWaypoint target = graph.findNearestWaypoint(targetPos);
        if (start == null || target == null) {
            graph.status().lastFailure = "No nearby navigation waypoint";
            return List.of();
        }
        graph.status().currentWaypoint = start.id;
        graph.status().targetWaypoint = target.id;
        if (start.id.equals(target.id)) {
            return List.of(start);
        }

        long now = System.currentTimeMillis();
        PriorityQueue<Record> open = new PriorityQueue<>(Comparator.comparingDouble(r -> r.f));
        Map<String, Double> gScore = new HashMap<>();
        Map<String, Step> cameFrom = new HashMap<>();
        gScore.put(start.id, 0.0);
        open.add(new Record(start.id, heuristic(start, target)));

        while (!open.isEmpty()) {
            Record current = open.poll();
            NavigationWaypoint currentWaypoint = graph.getWaypoint(current.id);
            if (currentWaypoint == null) {
                continue;
            }
            if (current.id.equals(target.id)) {
                return reconstruct(cameFrom, current.id);
            }
            double currentG = gScore.getOrDefault(current.id, Double.MAX_VALUE);
            for (NavigationEdge edge : graph.edgesFrom(current.id)) {
                if (edge.isTemporarilyInvalid(now)) {
                    continue;
                }
                String nextId = edge.other(current.id);
                NavigationWaypoint next = graph.getWaypoint(nextId);
                if (next == null) {
                    continue;
                }
                double movementCost = graph.movementCosts().multiplierFor(next.type);
                double tentative = currentG + Math.max(0.01, edge.cost) * movementCost;
                if (tentative < gScore.getOrDefault(nextId, Double.MAX_VALUE)) {
                    cameFrom.put(nextId, new Step(current.id, edge));
                    gScore.put(nextId, tentative);
                    open.add(new Record(nextId, tentative + heuristic(next, target)));
                }
            }
        }
        graph.status().lastFailure = "No global waypoint route";
        return List.of();
    }

    private List<NavigationWaypoint> reconstruct(Map<String, Step> cameFrom, String targetId) {
        ArrayList<NavigationWaypoint> out = new ArrayList<>();
        String cur = targetId;
        while (cur != null) {
            NavigationWaypoint waypoint = graph.getWaypoint(cur);
            if (waypoint != null) {
                out.add(waypoint);
            }
            Step step = cameFrom.get(cur);
            cur = step == null ? null : step.previousId;
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static double heuristic(NavigationWaypoint a, NavigationWaypoint b) {
        return Math.sqrt(a.distanceSq(b));
    }

    private record Record(String id, double f) {
    }

    private record Step(String previousId, NavigationEdge edge) {
    }
}
