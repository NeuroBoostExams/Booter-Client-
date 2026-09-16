package com.booter.client.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NavigationWaypoint {
    public static final String NORMAL = "normal";
    public static final String STAIRS = "stairs";
    public static final String LADDER = "ladder";
    public static final String JUMP = "jump";
    public static final String BRIDGE = "bridge";
    public static final String DOOR = "door";
    public static final String TELEPORT = "teleport";
    public static final String MINECART = "minecart";
    public static final String BOAT = "boat";

    public String id = "";
    public int x;
    public int y;
    public int z;
    public String region = "default";
    public String type = NORMAL;
    public Map<String, String> metadata = new LinkedHashMap<>();

    public NavigationWaypoint() {
    }

    public NavigationWaypoint(String id, int x, int y, int z, String region, String type) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.region = region == null || region.isBlank() ? "default" : region;
        this.type = type == null || type.isBlank() ? NORMAL : type;
    }

    public BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }

    public Vec3 center() {
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    public double distanceSq(Vec3 pos) {
        double dx = x + 0.5 - pos.x;
        double dy = y - pos.y;
        double dz = z + 0.5 - pos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceSq(NavigationWaypoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
