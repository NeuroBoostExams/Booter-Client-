package com.booter.client.pathcore.pathing.result;

import com.booter.client.pathcore.wrapper.PathPosition;
import java.util.Collection;

public interface Path extends Iterable<PathPosition> {
    int length();
    PathPosition getStart();
    PathPosition getEnd();
    Collection<PathPosition> collect();
}
