package com.booter.client.navigation;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class NavigationPath {
    private final List<NavigationWaypoint> globalWaypoints;
    private final List<BlockPos> localNodes;
    private final boolean hierarchical;
    private final String failureReason;

    private NavigationPath(List<NavigationWaypoint> globalWaypoints, List<BlockPos> localNodes,
                           boolean hierarchical, String failureReason) {
        this.globalWaypoints = List.copyOf(globalWaypoints);
        this.localNodes = List.copyOf(localNodes);
        this.hierarchical = hierarchical;
        this.failureReason = failureReason;
    }

    public static NavigationPath found(List<NavigationWaypoint> globalWaypoints, List<BlockPos> localNodes, boolean hierarchical) {
        return new NavigationPath(globalWaypoints, localNodes, hierarchical, "");
    }

    public static NavigationPath failed(String reason) {
        return new NavigationPath(List.of(), List.of(), false, reason);
    }

    public boolean found() {
        return localNodes.size() >= 2;
    }

    public List<NavigationWaypoint> globalWaypoints() {
        return globalWaypoints;
    }

    public List<BlockPos> localNodes() {
        return localNodes;
    }

    public boolean hierarchical() {
        return hierarchical;
    }

    public String failureReason() {
        return failureReason;
    }
}
