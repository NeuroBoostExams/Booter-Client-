package com.booter.client.navigation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PathCache {
    private final Map<String, Entry> entries = new HashMap<>();

    public List<NavigationWaypoint> get(String startId, String targetId, int version, long maxAgeMs) {
        Entry entry = entries.get(key(startId, targetId));
        long now = System.currentTimeMillis();
        if (entry == null || entry.version != version || now - entry.createdMs > maxAgeMs) {
            return null;
        }
        return entry.route;
    }

    public void put(String startId, String targetId, int version, List<NavigationWaypoint> route) {
        if (startId == null || targetId == null || route == null || route.isEmpty()) {
            return;
        }
        entries.put(key(startId, targetId), new Entry(List.copyOf(route), version, System.currentTimeMillis()));
    }

    public void invalidateEdge(NavigationEdge edge) {
        if (edge == null) {
            return;
        }
        entries.entrySet().removeIf(e -> routeContainsEdge(e.getValue().route, edge));
    }

    public void clear() {
        entries.clear();
    }

    private static boolean routeContainsEdge(List<NavigationWaypoint> route, NavigationEdge edge) {
        for (int i = 0; i + 1 < route.size(); i++) {
            String a = route.get(i).id;
            String b = route.get(i + 1).id;
            if ((edge.from.equals(a) && edge.to.equals(b)) || (edge.bidirectional && edge.from.equals(b) && edge.to.equals(a))) {
                return true;
            }
        }
        return false;
    }

    private static String key(String startId, String targetId) {
        return startId + "->" + targetId;
    }

    private record Entry(List<NavigationWaypoint> route, int version, long createdMs) {
    }
}
