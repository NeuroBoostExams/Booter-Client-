package com.booter.client.navigation;

public final class NavigationStatus {
    public String status = "idle";
    public String currentWaypoint = "";
    public String targetWaypoint = "";
    public double progress;
    public double distanceRemaining;
    public int replans;
    public int currentSegment;
    public String lastFailure = "";

    public void idle() {
        status = "idle";
        currentWaypoint = "";
        targetWaypoint = "";
        progress = 0.0;
        distanceRemaining = 0.0;
        currentSegment = 0;
        lastFailure = "";
    }
}
