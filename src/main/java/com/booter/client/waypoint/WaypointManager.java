package com.booter.client.waypoint;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Owns the ordered waypoint list and its persistence. The working route is
 * auto-saved to {@code config/booterclient/waypoints.json} after every change
 * so it survives game restarts; named routes can be exported to / imported
 * from plain JSON files in {@code config/booterclient/routes/}.
 */
public final class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ROUTE_TYPE = new TypeToken<List<Waypoint>>() {}.getType();

    private final ConfigManager config;
    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<Waypoint> unmodifiableView = Collections.unmodifiableList(waypoints);

    public WaypointManager(ConfigManager config) {
        this.config = config;
    }

    public List<Waypoint> view() {
        return unmodifiableView;
    }

    public int size() {
        return waypoints.size();
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    public Waypoint get(int index) {
        return waypoints.get(index);
    }

    /** @return the 1-based number of the new waypoint. */
    public int add(double x, double y, double z) {
        return add(x, y, z, Waypoint.Type.ARRIVED);
    }

    /** @return the 1-based number of the new waypoint. */
    public int add(double x, double y, double z, Waypoint.Type type) {
        waypoints.add(new Waypoint(x, y, z, type));
        autosave();
        return waypoints.size();
    }

    /**
     * Removes the waypoint closest to the given position if it is within
     * {@code maxDistance} blocks.
     *
     * @return the removed waypoint's 1-based number, or -1 if none was in range.
     */
    public int removeNearest(double px, double py, double pz, double maxDistance) {
        int best = -1;
        double bestSq = maxDistance * maxDistance;
        for (int i = 0; i < waypoints.size(); i++) {
            double sq = waypoints.get(i).squaredDistanceTo(px, py, pz);
            if (sq <= bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        if (best < 0) {
            return -1;
        }
        waypoints.remove(best);
        autosave();
        return best + 1;
    }

    public boolean removeAt(int index) {
        if (index < 0 || index >= waypoints.size()) {
            return false;
        }
        waypoints.remove(index);
        autosave();
        return true;
    }

    /** @return how many waypoints were removed. */
    public int clear() {
        int count = waypoints.size();
        waypoints.clear();
        autosave();
        return count;
    }

    /**
     * Loads a route bundled inside the mod jar at
     * {@code /booterclient/routes/<name>.json}, replacing the current list.
     * This is how hardcoded macro routes (e.g. "mushroom") ship with the mod.
     *
     * @return the number of waypoints loaded, or -1 if the resource is missing/invalid.
     */
    public int loadBundledRoute(String name) {
        String path = "/booterclient/routes/" + sanitize(name) + ".json";
        try (var stream = WaypointManager.class.getResourceAsStream(path)) {
            if (stream == null) {
                BooterClient.LOGGER.error("Bundled route not found: {}", path);
                return -1;
            }
            try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
                List<Waypoint> loaded = GSON.fromJson(reader, ROUTE_TYPE);
                if (loaded == null) {
                    return -1;
                }
                waypoints.clear();
                waypoints.addAll(loaded);
                autosave();
                return waypoints.size();
            }
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to read bundled route {}", path, e);
            return -1;
        }
    }

    /** @return the names (without .json) of saved routes in the routes folder, sorted. */
    public List<String> listRoutes() {
        List<String> names = new ArrayList<>();
        Path dir = config.routesDir();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                String f = p.getFileName().toString();
                if (f.endsWith(".json")) {
                    names.add(f.substring(0, f.length() - 5));
                }
            });
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to list routes", e);
        }
        Collections.sort(names);
        return names;
    }

    /** Loads the auto-saved working route from the previous session. */
    public void loadCurrent() {
        List<Waypoint> loaded = readRoute(config.currentRouteFile());
        if (loaded != null) {
            waypoints.clear();
            waypoints.addAll(loaded);
            BooterClient.LOGGER.info("Restored {} waypoint(s) from previous session", waypoints.size());
        }
    }

    /**
     * Exports the current route to {@code config/booterclient/routes/<name>.json}.
     *
     * @return the file name written, or null on failure.
     */
    public String saveRoute(String name) {
        String fileName = sanitize(name) + ".json";
        Path file = config.routesDir().resolve(fileName);
        if (writeRoute(file)) {
            return fileName;
        }
        return null;
    }

    /**
     * Imports a route from {@code config/booterclient/routes/<name>.json},
     * replacing the current waypoint list.
     *
     * @return the number of waypoints loaded, or -1 if the file is missing/invalid.
     */
    public int loadRoute(String name) {
        Path file = config.routesDir().resolve(sanitize(name) + ".json");
        List<Waypoint> loaded = readRoute(file);
        if (loaded == null) {
            return -1;
        }
        waypoints.clear();
        waypoints.addAll(loaded);
        autosave();
        return waypoints.size();
    }

    private void autosave() {
        writeRoute(config.currentRouteFile());
    }

    private boolean writeRoute(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file)) {
                GSON.toJson(waypoints, ROUTE_TYPE, writer);
            }
            return true;
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to write route file {}", file, e);
            return false;
        }
    }

    private List<Waypoint> readRoute(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try (var reader = Files.newBufferedReader(file)) {
            List<Waypoint> loaded = GSON.fromJson(reader, ROUTE_TYPE);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to read route file {}", file, e);
            return null;
        }
    }

    private static String sanitize(String name) {
        String cleaned = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        return cleaned.isBlank() ? "route" : cleaned;
    }
}
