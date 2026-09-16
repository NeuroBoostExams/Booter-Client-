package com.booter.client.pathcore.pathing;

import com.booter.client.pathcore.wrapper.PathPosition;

public final class PathfindingProgress {
    public final PathPosition start;
    public final PathPosition current;
    public final PathPosition target;

    public PathfindingProgress(PathPosition start, PathPosition current, PathPosition target) {
        this.start = start;
        this.current = current;
        this.target = target;
    }
}
