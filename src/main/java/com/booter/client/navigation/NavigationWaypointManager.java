package com.booter.client.navigation;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NavigationWaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CELL_SIZE = 64;

    private final ConfigManager config;
    private final Path graphFile;
    private final Map<String, NavigationWaypoint> waypointsById = new LinkedHashMap<>();
    private final Map<String, List<NavigationEdge>> edgesByFrom = new HashMap<>();
    private final Map<Long, List<NavigationWaypoint>> spatial = new HashMap<>();
    private final PathCache cache = new PathCache();
    private final NavigationStatus status = new NavigationStatus();
    private final GlobalPathfinder globalPathfinder;
    private final HierarchicalPathfinder hierarchicalPathfinder;
    private NavigationGraphData data = new NavigationGraphData();
    private long version;

    public NavigationWaypointManager(ConfigManager config) {
        this.config = config;
        this.graphFile = FabricLoader.getInstance().getConfigDir()
                .resolve(BooterClient.MOD_ID)
                .resolve("navigation_graph.json");
        this.globalPathfinder = new GlobalPathfinder(this);
        this.hierarchicalPathfinder = new HierarchicalPathfinder(config, this, globalPathfinder, cache);
    }

    public void loadWaypoints() {
        try {
            Files.createDirectories(graphFile.getParent());
            if (Files.exists(graphFile)) {
                try (var reader = Files.newBufferedReader(graphFile)) {
                    NavigationGraphData loaded = GSON.fromJson(reader, NavigationGraphData.class);
                    data = loaded == null ? new NavigationGraphData() : loaded;
                }
            } else {
                data = exampleGraph();
                saveWaypoints();
            }
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to load navigation graph", e);
            data = new NavigationGraphData();
        }
        rebuildIndexes();
    }

    public void saveWaypoints() {
        try {
            Files.createDirectories(graphFile.getParent());
            try (var writer = Files.newBufferedWriter(graphFile)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to save navigation graph", e);
        }
    }

    public NavigationWaypoint getWaypoint(String id) {
        return id == null ? null : waypointsById.get(id);
    }

    public List<NavigationWaypoint> getNearbyWaypoints(Vec3 position, double radius) {
        if (position == null || radius <= 0.0) {
            return List.of();
        }
        double radiusSq = radius * radius;
        int minX = cell(position.x - radius);
        int maxX = cell(position.x + radius);
        int minZ = cell(position.z - radius);
        int maxZ = cell(position.z + radius);
        List<NavigationWaypoint> out = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                List<NavigationWaypoint> bucket = spatial.get(packCell(cx, cz));
                if (bucket == null) {
                    continue;
                }
                for (NavigationWaypoint waypoint : bucket) {
                    if (waypoint.distanceSq(position) <= radiusSq) {
                        out.add(waypoint);
                    }
                }
            }
        }
        return out;
    }

    public NavigationWaypoint findNearestWaypoint(Vec3 position) {
        List<NavigationWaypoint> nearby = getNearbyWaypoints(position, config.settings.hierarchyWaypointSearchRadius);
        NavigationWaypoint best = null;
        double bestSq = Double.MAX_VALUE;
        for (NavigationWaypoint waypoint : nearby) {
            double dist = waypoint.distanceSq(position);
            if (dist < bestSq) {
                bestSq = dist;
                best = waypoint;
            }
        }
        return best;
    }

    public void addWaypoint(NavigationWaypoint waypoint) {
        if (waypoint == null || waypoint.id == null || waypoint.id.isBlank()) {
            return;
        }
        data.waypoints.removeIf(w -> waypoint.id.equals(w.id));
        data.waypoints.add(waypoint);
        rebuildIndexes();
        saveWaypoints();
    }

    public boolean removeWaypoint(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        boolean removed = data.waypoints.removeIf(w -> id.equals(w.id));
        if (removed) {
            data.edges.removeIf(e -> id.equals(e.from) || id.equals(e.to));
            rebuildIndexes();
            saveWaypoints();
        }
        return removed;
    }

    public void updateWaypoint(NavigationWaypoint waypoint) {
        addWaypoint(waypoint);
    }

    public void addEdge(NavigationEdge edge) {
        if (edge == null || edge.from == null || edge.to == null || edge.from.isBlank() || edge.to.isBlank()) {
            return;
        }
        data.edges.removeIf(e -> e.from.equals(edge.from) && e.to.equals(edge.to));
        data.edges.add(edge);
        rebuildIndexes();
        saveWaypoints();
    }

    public List<NavigationEdge> edgesFrom(String waypointId) {
        return edgesByFrom.getOrDefault(waypointId, List.of());
    }

    public void markEdgeInvalid(NavigationEdge edge, String reason) {
        if (edge == null) {
            return;
        }
        edge.invalidateFor(config.settings.hierarchyEdgeInvalidSeconds * 1000L, reason);
        cache.invalidateEdge(edge);
    }

    public boolean hasGraph() {
        return waypointsById.size() >= 2 && !data.edges.isEmpty();
    }

    public boolean enabled() {
        return config.settings.hierarchicalPathfinding;
    }

    public int version() {
        return (int) version;
    }

    public MovementCosts movementCosts() {
        if (data.movementCosts == null) {
            data.movementCosts = new MovementCosts();
        }
        return data.movementCosts;
    }

    public Path graphFile() {
        return graphFile;
    }

    public NavigationStatus status() {
        return status;
    }

    public HierarchicalPathfinder pathfinder() {
        return hierarchicalPathfinder;
    }

    public NavigationPath navigateTo(net.minecraft.world.level.Level level, BlockPos start, BlockPos goal,
                                     com.booter.client.pathfinder.Pathfinder localPathfinder) {
        return hierarchicalPathfinder.findPath(level, start, goal, localPathfinder);
    }

    public Collection<NavigationWaypoint> waypoints() {
        return waypointsById.values();
    }

    public List<NavigationEdge> edges() {
        return data.edges;
    }

    private void rebuildIndexes() {
        waypointsById.clear();
        edgesByFrom.clear();
        spatial.clear();
        if (data.waypoints == null) {
            data.waypoints = new ArrayList<>();
        }
        if (data.edges == null) {
            data.edges = new ArrayList<>();
        }
        for (NavigationWaypoint waypoint : data.waypoints) {
            normalize(waypoint);
            waypointsById.put(waypoint.id, waypoint);
            spatial.computeIfAbsent(packCell(cell(waypoint.x), cell(waypoint.z)), k -> new ArrayList<>()).add(waypoint);
        }
        for (NavigationEdge edge : data.edges) {
            if (edge.from == null || edge.to == null || !waypointsById.containsKey(edge.from) || !waypointsById.containsKey(edge.to)) {
                continue;
            }
            edgesByFrom.computeIfAbsent(edge.from, k -> new ArrayList<>()).add(edge);
            if (edge.bidirectional) {
                edgesByFrom.computeIfAbsent(edge.to, k -> new ArrayList<>()).add(edge);
            }
        }
        version++;
        cache.clear();
    }

    private static void normalize(NavigationWaypoint waypoint) {
        if (waypoint.region == null || waypoint.region.isBlank()) {
            waypoint.region = "default";
        }
        if (waypoint.type == null || waypoint.type.isBlank()) {
            waypoint.type = NavigationWaypoint.NORMAL;
        }
        if (waypoint.metadata == null) {
            waypoint.metadata = new LinkedHashMap<>();
        }
    }

    private static int cell(double coord) {
        return (int) Math.floor(coord / CELL_SIZE);
    }

    private static long packCell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }

    private static NavigationGraphData exampleGraph() {
        NavigationGraphData graph = new NavigationGraphData();
        graph.waypoints.add(new NavigationWaypoint("example_spawn", 0, 64, 0, "example", NavigationWaypoint.NORMAL));
        graph.waypoints.add(new NavigationWaypoint("example_farm", 16, 64, 0, "example", NavigationWaypoint.NORMAL));
        graph.edges.add(new NavigationEdge("example_spawn", "example_farm", 16.0, true));
        return graph;
    }
}
