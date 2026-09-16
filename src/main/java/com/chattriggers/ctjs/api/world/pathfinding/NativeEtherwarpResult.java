package com.chattriggers.ctjs.api.world.pathfinding;

public final class NativeEtherwarpResult {
    public final int[] path;
    public final float[] angles;
    public final long timeMs;
    public final int nodesExplored;
    public final double nanosecondsPerNode;

    public NativeEtherwarpResult(int[] path, float[] angles, long timeMs, int nodesExplored, double nanosecondsPerNode) {
        this.path = path;
        this.angles = angles;
        this.timeMs = timeMs;
        this.nodesExplored = nodesExplored;
        this.nanosecondsPerNode = nanosecondsPerNode;
    }
}
