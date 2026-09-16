package com.booter.client.pathcore.result;

import com.booter.client.pathcore.pathing.result.Path;
import com.booter.client.pathcore.pathing.result.PathState;
import com.booter.client.pathcore.pathing.result.PathfinderResult;

public final class PathfinderResultImpl implements PathfinderResult {
    private final PathState state;
    private final Path path;

    public PathfinderResultImpl(PathState state, Path path) {
        this.state = state;
        this.path  = path;
    }

    @Override public boolean successful()    { return state == PathState.FOUND; }
    @Override public boolean hasFailed()     { return state == PathState.FAILED; }
    @Override public boolean hasFallenBack() {
        return state == PathState.FALLBACK
            || state == PathState.MAX_ITERATIONS_REACHED
            || state == PathState.LENGTH_LIMITED;
    }
    @Override public PathState getPathState() { return state; }
    @Override public Path getPath()           { return path; }
}
