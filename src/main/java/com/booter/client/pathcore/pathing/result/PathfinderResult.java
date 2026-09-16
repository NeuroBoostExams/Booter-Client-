package com.booter.client.pathcore.pathing.result;

public interface PathfinderResult {
    boolean successful();
    boolean hasFailed();
    boolean hasFallenBack();
    PathState getPathState();
    Path getPath();
}
