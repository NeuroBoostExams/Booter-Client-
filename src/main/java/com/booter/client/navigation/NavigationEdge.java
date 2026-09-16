package com.booter.client.navigation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NavigationEdge {
    public String from = "";
    public String to = "";
    public double cost = 1.0;
    public boolean bidirectional = true;
    public Map<String, String> metadata = new LinkedHashMap<>();

    private transient long invalidUntilMs;
    private transient String invalidReason = "";

    public NavigationEdge() {
    }

    public NavigationEdge(String from, String to, double cost, boolean bidirectional) {
        this.from = from;
        this.to = to;
        this.cost = Math.max(0.01, cost);
        this.bidirectional = bidirectional;
    }

    public boolean connects(String node) {
        return from.equals(node) || (bidirectional && to.equals(node));
    }

    public String other(String node) {
        if (from.equals(node)) {
            return to;
        }
        if (bidirectional && to.equals(node)) {
            return from;
        }
        return null;
    }

    public boolean isTemporarilyInvalid(long nowMs) {
        return nowMs < invalidUntilMs;
    }

    public void invalidateFor(long millis, String reason) {
        invalidUntilMs = System.currentTimeMillis() + Math.max(1000L, millis);
        invalidReason = reason == null ? "blocked" : reason;
    }

    public String invalidReason() {
        return invalidReason;
    }

    public String cacheKey() {
        return from + "->" + to;
    }
}
