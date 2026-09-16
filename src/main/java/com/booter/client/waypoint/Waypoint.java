package com.booter.client.waypoint;

/**
 * A single route point. Coordinates are the player's feet position at the
 * moment the waypoint was recorded. Mutable POJO so Gson can (de)serialize it.
 */
public final class Waypoint {
    public enum Type {
        WALKING,
        ARRIVED
    }

    public double x;
    public double y;
    public double z;
    public Type type = Type.ARRIVED;

    public Waypoint() {
    }

    public Waypoint(double x, double y, double z) {
        this(x, y, z, Type.ARRIVED);
    }

    public Waypoint(double x, double y, double z, Type type) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type == null ? Type.ARRIVED : type;
    }

    public boolean isWalking() {
        return type == Type.WALKING;
    }

    public boolean isArrived() {
        return type != Type.WALKING;
    }

    public double squaredDistanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return String.format("%s (%.1f, %.1f, %.1f)", type, x, y, z);
    }
}
