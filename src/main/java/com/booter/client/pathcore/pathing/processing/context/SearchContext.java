package com.booter.client.pathcore.pathing.processing.context;

import com.booter.client.pathcore.pathing.configuration.PathfinderConfiguration;
import com.booter.client.pathcore.pathing.context.EnvironmentContext;
import com.booter.client.pathcore.provider.NavigationPointProvider;
import com.booter.client.pathcore.wrapper.PathPosition;
import java.util.Map;

public interface SearchContext {
    PathPosition getStartPathPosition();
    PathPosition getTargetPathPosition();
    PathfinderConfiguration getPathfinderConfiguration();
    NavigationPointProvider getNavigationPointProvider();
    Map<String, Object> getSharedData();
    EnvironmentContext getEnvironmentContext();
}
