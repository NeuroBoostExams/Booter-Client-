package com.booter.client.pathcore.result;

import com.booter.client.pathcore.pathing.result.Path;
import com.booter.client.pathcore.wrapper.PathPosition;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class PathImpl implements Path {
    private final PathPosition start;
    private final PathPosition end;
    private final List<PathPosition> positions;

    public PathImpl(PathPosition start, PathPosition end, Collection<PathPosition> positions) {
        this.start = start;
        this.end   = end;
        this.positions = positions instanceof List<PathPosition> l ? l : List.copyOf(positions);
    }

    @Override public int length()                    { return positions.size(); }
    @Override public PathPosition getStart()         { return start; }
    @Override public PathPosition getEnd()           { return end; }
    @Override public Collection<PathPosition> collect() { return Collections.unmodifiableList(positions); }
    @Override public Iterator<PathPosition> iterator()  { return positions.iterator(); }
}
