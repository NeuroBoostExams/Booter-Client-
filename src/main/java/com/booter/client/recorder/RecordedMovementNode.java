package com.booter.client.recorder;

public final class RecordedMovementNode {
    public enum Type {
        WALK,
        END
    }

    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public Type type = Type.WALK;

    public RecordedMovementNode() {
    }

    public RecordedMovementNode(double x, double y, double z, float yaw, float pitch, Type type) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.type = type == null ? Type.WALK : type;
    }

    public double squaredDistanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
