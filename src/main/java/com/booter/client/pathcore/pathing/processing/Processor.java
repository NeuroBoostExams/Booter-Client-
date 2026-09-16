package com.booter.client.pathcore.pathing.processing;

import com.booter.client.pathcore.pathing.processing.context.SearchContext;

public interface Processor {
    default void initializeSearch(SearchContext context) {}
    default void finalizeSearch(SearchContext context) {}
}
