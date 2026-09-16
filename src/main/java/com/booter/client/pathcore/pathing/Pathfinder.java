package com.booter.client.pathcore.pathing;

import com.booter.client.pathcore.pathing.context.EnvironmentContext;
import com.booter.client.pathcore.pathing.result.PathfinderResult;
import com.booter.client.pathcore.wrapper.PathPosition;
import java.util.concurrent.CompletionStage;

public interface Pathfinder {
    default CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target) {
        return findPath(start, target, null);
    }

    CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target,
                                               EnvironmentContext context);

    void abort();
}
