package com.booter.client.navigation;

import java.util.ArrayList;
import java.util.List;

public final class NavigationGraphData {
    public List<NavigationWaypoint> waypoints = new ArrayList<>();
    public List<NavigationEdge> edges = new ArrayList<>();
    public MovementCosts movementCosts = new MovementCosts();
}
