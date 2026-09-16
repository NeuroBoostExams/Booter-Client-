package com.booter.client.pathcore.provider;

public interface NavigationPoint {
    boolean isTraversable();
    boolean hasFloor();
    double getFloorLevel();
    boolean isClimbable();
    boolean isLiquid();
}
