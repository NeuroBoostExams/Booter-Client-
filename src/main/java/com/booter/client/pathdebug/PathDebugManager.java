package com.booter.client.pathdebug;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central store for the A* search visualization. The pathfinder feeds it open
 * and closed nodes (and the final path) while it searches; the renderer and the
 * debug overlay read from it each frame.
 *
 * <p>All access is on the client thread (the pathfinder runs inside the client
 * tick and the renderer/overlay run on the same thread), so no locking is
 * needed. Node positions are stored as packed {@code long}s (no per-node object
 * churn) so it scales to thousands of nodes; the renderer decodes them with
 * {@link BlockPos#getX(long)} etc. without allocating.
 */
public final class PathDebugManager {
    private static final PathDebugManager INSTANCE = new PathDebugManager();

    public static PathDebugManager get() {
        return INSTANCE;
    }

    public final PathDebugSettings settings = new PathDebugSettings();

    // Explored ("closed") nodes — append-only growable array, iterated by index.
    private long[] closed = new long[4096];
    private int closedCount;
    // Open ("frontier") nodes — order-preserving set for cheap removal on close.
    private final java.util.LinkedHashSet<Long> open = new java.util.LinkedHashSet<>();

    private final List<BlockPos> finalPath = new ArrayList<>();
    private final List<BlockPos> finalPathView = Collections.unmodifiableList(finalPath);
    private BlockPos target;
    private BlockPos destination;

    // Statistics
    private long computeStartNanos;
    private long computeTimeMs;
    private long lastRenderNanos;
    private double avgRenderMs;

    private PathDebugManager() {
    }

    // ------------------------------------------------------------------ API (spec)

    public void addOpenNode(BlockPos pos) {
        addOpen(pos.asLong());
    }

    public void addClosedNode(BlockPos pos) {
        addClosed(pos.asLong());
    }

    public void setFinalPath(List<BlockPos> path) {
        finalPath.clear();
        if (path != null) {
            finalPath.addAll(path);
            if (!path.isEmpty()) {
                destination = path.get(path.size() - 1);
            }
        }
    }

    public void clearPath() {
        finalPath.clear();
        destination = null;
    }

    public void clearNodes() {
        closedCount = 0;
        open.clear();
    }

    public void setTarget(BlockPos target) {
        this.target = target;
    }

    // ------------------------------------------------------------------ allocation-free hooks

    /** Records a closed node by packed position (used by the pathfinder's hot loop). */
    public void addClosed(long packed) {
        open.remove(packed);
        if (closedCount >= closed.length) {
            closed = java.util.Arrays.copyOf(closed, closed.length * 2);
        }
        closed[closedCount++] = packed;
    }

    public void addOpen(long packed) {
        open.add(packed);
    }

    /** Begins a new search: resets nodes/path and starts the compute timer. */
    public void beginSearch(BlockPos target) {
        clearNodes();
        clearPath();
        this.target = target;
        this.destination = null;
        computeStartNanos = System.nanoTime();
    }

    /** Ends the current search and records how long it took. */
    public void endSearch() {
        computeTimeMs = Math.round((System.nanoTime() - computeStartNanos) / 1_000_000.0);
    }

    /** Called by the renderer with the nanos it spent drawing, for the FPS-impact readout. */
    public void recordRenderNanos(long nanos) {
        lastRenderNanos = nanos;
        // Exponential moving average so the number is stable, not jumpy.
        avgRenderMs = avgRenderMs * 0.9 + (nanos / 1_000_000.0) * 0.1;
    }

    // ------------------------------------------------------------------ reads

    public long[] closedArray() {
        return closed;
    }

    public int closedCount() {
        return closedCount;
    }

    public java.util.Set<Long> openNodes() {
        return open;
    }

    public List<BlockPos> finalPath() {
        return finalPathView;
    }

    public BlockPos target() {
        return target;
    }

    public BlockPos destination() {
        return destination;
    }

    public int nodesExplored() {
        return closedCount;
    }

    public int openCount() {
        return open.size();
    }

    public int pathLength() {
        return finalPath.size();
    }

    public long computeTimeMs() {
        return computeTimeMs;
    }

    public double avgRenderMs() {
        return avgRenderMs;
    }
}
