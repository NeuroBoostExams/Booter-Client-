package com.booter.client.pathdebug;

/**
 * Configuration for the A* search visualizer. Plain mutable holder so it can be
 * driven from a GUI/keybind and read cheaply every frame.
 *
 * <p>Per-node colours and their base opacities are fixed by design (red explored,
 * yellow open, green path, blue target); {@link #nodeOpacity} is a global
 * multiplier on top, and the path line colour/thickness are fully configurable.
 */
public final class PathDebugSettings {
    /** Master switch — when off, nothing is recorded or drawn (zero overhead). */
    public boolean enabled = false;

    public boolean renderExploredNodes = true;
    public boolean renderOpenNodes = true;
    public boolean renderFinalPath = true;
    public boolean renderDestination = true;
    public boolean showPathStatistics = true;

    /** Path line width in pixels. */
    public float lineThickness = 5.0f;
    /** Node cube half-extent in blocks. */
    public float nodeSize = 0.22f;
    /** Global opacity multiplier applied to every node's base alpha (0..1). */
    public float nodeOpacity = 1.0f;
    /** Nodes farther than this (blocks) from the camera are skipped. */
    public int maxRenderDistance = 64;
    /** Configurable path line colour (RGB; alpha is taken from the path opacity). */
    public int lineColor = 0xFF40C4FF;
}
