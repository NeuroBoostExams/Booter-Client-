package com.booter.client.pathdebug;

import com.booter.client.BooterClient;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * On-screen statistics panel for the A* search visualizer, drawn as a Fabric HUD
 * element (26.1 render-state extraction model). Shows nodes explored, open-set
 * size, final path length, current target, compute time and the renderer's
 * average frame cost. Only drawn when the visualizer and statistics are enabled.
 */
public final class PathDebugOverlay implements HudElement {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(BooterClient.MOD_ID, "path_debug");

    private static final int PAD = 4;
    private static final int LINE_H = 10;
    private static final int COLOR_BG = 0xB0101418;
    private static final int COLOR_TITLE = 0xFF40C4FF;
    private static final int COLOR_LABEL = 0xFF9AA7B0;
    private static final int COLOR_VALUE = 0xFFFFFFFF;

    // Reused so the per-frame extract allocates nothing but the formatted strings.
    private final String[] labels = {"Nodes Explored", "Open Nodes", "Path Length", "Target", "Compute", "Avg Render"};
    private final String[] values = new String[labels.length];

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        PathDebugManager mgr = PathDebugManager.get();
        PathDebugSettings s = mgr.settings;
        if (!s.enabled || !s.showPathStatistics) {
            return;
        }
        Font font = Minecraft.getInstance().font;

        values[0] = Integer.toString(mgr.nodesExplored());
        values[1] = Integer.toString(mgr.openCount());
        values[2] = Integer.toString(mgr.pathLength());
        BlockPos t = mgr.target();
        values[3] = t == null ? "—" : t.getX() + ", " + t.getY() + ", " + t.getZ();
        values[4] = mgr.computeTimeMs() + " ms";
        values[5] = String.format("%.2f ms", mgr.avgRenderMs());

        // Panel width = widest "label: value" row.
        int titleW = font.width("A* Search Visualizer");
        int contentW = titleW;
        for (int i = 0; i < labels.length; i++) {
            contentW = Math.max(contentW, font.width(labels[i] + ": " + values[i]));
        }

        int x = PAD;
        int y = PAD;
        int w = contentW + PAD * 2;
        int h = PAD * 2 + LINE_H * (labels.length + 1);
        graphics.fill(x, y, x + w, y + h, COLOR_BG);

        int tx = x + PAD;
        int ty = y + PAD;
        graphics.text(font, "A* Search Visualizer", tx, ty, COLOR_TITLE, true);
        ty += LINE_H;
        for (int i = 0; i < labels.length; i++) {
            graphics.text(font, labels[i] + ": ", tx, ty, COLOR_LABEL, true);
            graphics.text(font, values[i], tx + font.width(labels[i] + ": "), ty, COLOR_VALUE, true);
            ty += LINE_H;
        }
    }
}
