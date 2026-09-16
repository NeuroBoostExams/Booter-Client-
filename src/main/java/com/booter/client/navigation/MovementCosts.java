package com.booter.client.navigation;

public final class MovementCosts {
    public double walk = 1.0;
    public double sprint = 0.8;
    public double jump = 2.0;
    public double climb = 3.0;
    public double difficult = 5.0;
    public double dangerous = 100.0;
    public double impossible = 1.0e9;

    public double multiplierFor(String type) {
        if (NavigationWaypoint.LADDER.equals(type)) return climb;
        if (NavigationWaypoint.JUMP.equals(type)) return jump;
        if (NavigationWaypoint.STAIRS.equals(type)) return difficult;
        if (NavigationWaypoint.BOAT.equals(type) || NavigationWaypoint.MINECART.equals(type)) return sprint;
        return walk;
    }
}
