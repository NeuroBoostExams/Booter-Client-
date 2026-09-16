package com.booter.client.pathcore.provider;

import com.booter.client.pathcore.pathing.context.EnvironmentContext;
import com.booter.client.pathcore.wrapper.PathPosition;

public interface NavigationPointProvider {
    default NavigationPoint getNavigationPoint(PathPosition position) {
        return getNavigationPoint(position, null);
    }

    NavigationPoint getNavigationPoint(PathPosition position, EnvironmentContext environmentContext);
}
