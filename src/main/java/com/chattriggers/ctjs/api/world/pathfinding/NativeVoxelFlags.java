package com.chattriggers.ctjs.api.world.pathfinding;

public final class NativeVoxelFlags {
    public static final int PASSABLE = 1;
    public static final int SOLID = 1 << 1;
    public static final int PASSABLE_FLY = 1 << 2;
    public static final int BLOCKING_WALL = 1 << 3;
    public static final int FLUID = 1 << 4;
    public static final int SLAB_BOTTOM = 1 << 5;
    public static final int SLAB_TOP = 1 << 6;
    public static final int FENCE_LIKE = 1 << 7;
    public static final int STAIRS_BOTTOM = 1 << 8;
    public static final int CARPET_LIKE = 1 << 9;
    public static final int ETHER_PASSABLE = 1 << 10;
    public static final int ETHER_TELEPORT_CLEAR = 1 << 11;
    public static final int ETHER_FEET_BLOCKER = 1 << 12;
    public static final int ETHER_FAKE_FULL_BLOCKER = 1 << 13;

    private NativeVoxelFlags() {
    }
}
