package com.booter.client.pathcore.pathing.heuristic;

import com.booter.client.pathcore.wrapper.PathPosition;

public interface IHeuristicStrategy {
    double calculate(HeuristicContext context);
    double calculateTransitionCost(PathPosition from, PathPosition to);
}
