package com.booter.client.pathcore.debug;

/**
 * No-op replacement for imported in-client path visualizer. Booter Client uses
 * RouteRenderer for path display, but imported A* keeps this hook for optional
 * explored-node profiling.
 */
public final class PathVisualizer {
    private PathVisualizer() {
    }

    public static boolean shouldCaptureExploredNodes() {
        return false;
    }
}
