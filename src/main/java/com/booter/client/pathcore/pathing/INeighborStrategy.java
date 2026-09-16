package com.booter.client.pathcore.pathing;

import com.booter.client.pathcore.wrapper.PathPosition;
import com.booter.client.pathcore.wrapper.PathVector;

@FunctionalInterface
public interface INeighborStrategy {
    Iterable<PathVector> getOffsets();

    default Iterable<PathVector> getOffsets(PathPosition currentPosition) {
        return getOffsets();
    }
}
