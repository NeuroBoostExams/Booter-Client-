package com.booter.client.pathcore.pathing.processing.context;

import com.booter.client.pathcore.pathing.configuration.PathfinderConfiguration;
import com.booter.client.pathcore.pathing.context.EnvironmentContext;
import com.booter.client.pathcore.provider.NavigationPointProvider;
import com.booter.client.pathcore.wrapper.PathPosition;
import java.util.Map;

public interface EvaluationContext {
    PathPosition getCurrentPathPosition();
    PathPosition getPreviousPathPosition();       // nullable
    int getCurrentNodeDepth();
    double getCurrentNodeHeuristicValue();
    double getPathCostToPreviousPosition();
    double getBaseTransitionCost();
    SearchContext getSearchContext();
    PathPosition getGrandparentPathPosition();    // nullable
    default PathPosition getGreatGrandparentPathPosition() { return null; }

    default PathfinderConfiguration getPathfinderConfiguration() {
        return getSearchContext().getPathfinderConfiguration();
    }

    default NavigationPointProvider getNavigationPointProvider() {
        return getSearchContext().getNavigationPointProvider();
    }

    default Map<String, Object> getSharedData() {
        return getSearchContext().getSharedData();
    }

    default PathPosition getStartPathPosition() {
        return getSearchContext().getStartPathPosition();
    }

    default PathPosition getTargetPathPosition() {
        return getSearchContext().getTargetPathPosition();
    }

    default EnvironmentContext getEnvironmentContext() {
        return getSearchContext().getEnvironmentContext();
    }
}
