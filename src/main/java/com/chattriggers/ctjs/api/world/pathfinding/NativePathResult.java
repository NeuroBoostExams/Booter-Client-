package com.chattriggers.ctjs.api.world.pathfinding;

public final class NativePathResult {
    public final int[] path;
    public final int[] keyPath;
    public final long timeMs;
    public final int nodesExplored;
    public final double nanosecondsPerNode;
    public final int selectedStartIndex;
    public final int[] pathFlags;
    public final int[] keyNodeFlags;
    public final int[] keyNodeMetrics;
    public final String pathSignature;

    public NativePathResult(int[] path, int[] keyPath, long timeMs, int nodesExplored, double nanosecondsPerNode,
                            int selectedStartIndex, int[] pathFlags, int[] keyNodeFlags,
                            int[] keyNodeMetrics, String pathSignature) {
        this.path = path;
        this.keyPath = keyPath;
        this.timeMs = timeMs;
        this.nodesExplored = nodesExplored;
        this.nanosecondsPerNode = nanosecondsPerNode;
        this.selectedStartIndex = selectedStartIndex;
        this.pathFlags = pathFlags;
        this.keyNodeFlags = keyNodeFlags;
        this.keyNodeMetrics = keyNodeMetrics;
        this.pathSignature = pathSignature;
    }
}
