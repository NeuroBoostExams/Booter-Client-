package com.booter.client.gui;

import com.booter.client.BooterClient;
import com.booter.client.WaypointWalkerModule;
import com.booter.client.config.ConfigManager;
import com.booter.client.pathfinder.PathfinderModule;
import com.booter.client.waypoint.Waypoint;
import com.booter.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Booter Client click GUI. A left-hand sidebar lists the available sections
 * (categories); the selected section's settings fill the content area on the
 * right. The "Route Walker" section holds every Waypoint Walker control plus a
 * scrollable waypoint list and JSON import/export.
 *
 * <p>Built for the Minecraft 26.1 render-state model: drawing happens in
 * {@link #extractRenderState} via {@link GuiGraphicsExtractor}, and input
 * arrives as event records ({@link MouseButtonEvent}, {@link KeyEvent},
 * {@link CharacterEvent}).
 */
public final class WaypointScreen extends Screen {
    // Theme
    private static final int COL_PANEL = 0xF20D1117;
    private static final int COL_SIDEBAR = 0xFF0B0F14;
    private static final int COL_HEADER = 0xFF161B22;
    private static final int COL_BORDER = 0xFF30363D;
    private static final int COL_TEXT = 0xFFE6EDF3;
    private static final int COL_DIM = 0xFF8B949E;
    private static final int COL_ACCENT = 0xFF2F81F7;
    private static final int COL_ON = 0xFF238636;
    private static final int COL_OFF = 0xFF30363D;
    private static final int COL_BUTTON = 0xFF21262D;
    private static final int COL_BUTTON_HOVER = 0xFF30363D;
    private static final int COL_DANGER = 0xFFB62324;
    private static final int COL_ACTIVE = 0xFF3FB950;
    private static final int COL_LIST_BG = 0xC0090C10;
    private static final int COL_ROW_HOVER = 0x4030363D;
    private static final int COL_TAB_SELECTED = 0xFF1F6FEB;
    private static final int COL_TAB = 0xFF161B22;

    private static final int LIST_ROW_H = 11;
    private static final int SIDEBAR_W = 96;

    /** Sidebar sections. Add more here as new modules are introduced. */
    private static final String[] SECTIONS = {"Route Walker", "Mushroom Macro", "Pathfinder", "Mushroom Farmer", "Block Miner", "Auto Fisher", "Fish Hunter", "Turtle Hunter", "Combat", "Coal Miner", "Auto Farmer", "Azalea Farmer", "Zealot Eman Farmer", "Path Debug", "Dev Mode", "Movement Recorder"};

    /** Cycleable presets for the debug path-line colour (ARGB). */
    private static final int[] DEBUG_LINE_COLORS = {0xFF40C4FF, 0xFF2CFF6A, 0xFFFFE34D, 0xFFFF5BAA, 0xFFB76BFF, 0xFFFFFFFF};

    private static final IntPredicate NAME_CHARS = c -> Character.isLetterOrDigit(c) || c == '_' || c == '-';
    private static final IntPredicate COORD_CHARS = c -> (c >= '0' && c <= '9') || c == '-';

    private final List<Widget> widgets = new ArrayList<>();
    private TextField nameField;
    private TextField focusedField;
    private int selectedSection;

    // Pathfinder target entry (string buffers parsed on Pathfind)
    private String tXBuf = "0";
    private String tYBuf = "64";
    private String tZBuf = "0";
    private int pfInnerX;
    private int pfCellW;
    private int pfFieldY;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private int contentX;
    private int contentW;

    private int tabX;
    private int tabY0;
    private int tabW;
    private int tabH;
    private int tabGap;

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private double listScroll;

    // Scrollable settings area (per section)
    private double contentScroll;
    private double contentMax;
    private int contentViewTop;
    private int contentViewBottom;

    // Saved-route browser (Block Miner section)
    private java.util.List<String> cachedRoutes = new ArrayList<>();
    private int minerRoutesLabelY;

    public WaypointScreen() {
        super(Component.literal("Booter Client"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    protected void init() {
        refreshRoutes();
        rebuild();
    }

    private void refreshRoutes() {
        cachedRoutes = BooterClient.waypoints().listRoutes();
    }

    /** Recomputes geometry and rebuilds the widgets for the selected section. */
    private void rebuild() {
        widgets.clear();

        panelW = Math.min(width - 20, 560);
        panelH = Math.min(height - 16, 300);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        contentX = panelX + SIDEBAR_W;
        contentW = panelW - SIDEBAR_W;

        // Sidebar section tabs
        tabX = panelX + 6;
        tabW = SIDEBAR_W - 12;
        tabH = 16;
        tabGap = Math.max(tabH, Math.min(19, (panelH - 44) / SECTIONS.length));
        tabY0 = panelY + 16 + 6;

        focusedField = null;
        if (selectedSection == 0) {
            buildRouteWalkerSection();
        } else if (selectedSection == 1) {
            buildMushroomSection();
        } else if (selectedSection == 2) {
            buildPathfinderSection();
        } else if (selectedSection == 3) {
            buildMushroomFarmerSection();
        } else if (selectedSection == 4) {
            buildBlockMinerSection();
        } else if (selectedSection == 5) {
            buildAutoFisherSection();
        } else if (selectedSection == 6) {
            buildFishHunterSection();
        } else if (selectedSection == 7) {
            buildTurtleHunterSection();
        } else if (selectedSection == 8) {
            buildCombatSection();
        } else if (selectedSection == 9) {
            buildCoalMinerSection();
        } else if (selectedSection == 10) {
            buildAutoFarmerSection();
        } else if (selectedSection == 11) {
            buildAzaleaFarmerSection();
        } else if (selectedSection == 12) {
            buildZealotEmanSection();
        } else if (selectedSection == 13) {
            buildPathDebugSection();
        } else if (selectedSection == 14) {
            buildDevModeSection();
        } else if (selectedSection == 15) {
            buildMovementRecorderSection();
        }
        applyContentScroll();
    }

    /**
     * Computes the scrollable viewport for the settings widgets and shifts them
     * by the current scroll amount. Widgets above/below the viewport are clipped
     * when rendered, so any section's settings can be scrolled through.
     */
    private void applyContentScroll() {
        if (widgets.isEmpty()) {
            contentMax = 0;
            contentViewTop = panelY + 16;
            contentViewBottom = panelY + panelH - 6;
            return;
        }
        int naturalTop = Integer.MAX_VALUE;
        int naturalBottom = Integer.MIN_VALUE;
        for (Widget w : widgets) {
            naturalTop = Math.min(naturalTop, w.y);
            naturalBottom = Math.max(naturalBottom, w.y + w.h);
        }
        contentViewTop = naturalTop;
        contentViewBottom = panelY + panelH - 6;
        int viewportH = contentViewBottom - contentViewTop;
        int contentH = naturalBottom - naturalTop;
        contentMax = Math.max(0, contentH - viewportH);
        contentScroll = Mth.clamp(contentScroll, 0.0, contentMax);
        int off = (int) contentScroll;
        if (off != 0) {
            for (Widget w : widgets) {
                w.y -= off;
            }
            pfFieldY -= off; // keep pathfinder's X/Y/Z labels aligned with their fields
            minerRoutesLabelY -= off; // keep the block miner's "Saved routes" label aligned
        }
    }

    private void buildRouteWalkerSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = (contentW - pad * 3) * 56 / 100;

        // Waypoint list occupies the right portion of the content area.
        listX = innerX + controlsW + pad;
        listW = contentX + contentW - pad - listX;
        listY = panelY + 16 + 15;
        listH = panelY + panelH - pad - listY;

        ConfigManager.Settings s = BooterClient.config().settings;
        WaypointWalkerModule walker = BooterClient.walker();

        int y = panelY + 16 + 15; // below the header + status line
        int halfW = (controlsW - 3) / 2;
        int thirdW = (controlsW - 6) / 3;

        // Route controls
        widgets.add(new Button(innerX, y, thirdW, 14, () -> "Start", false,
                () -> walker.startRoute(mc())));
        widgets.add(new Button(innerX + thirdW + 3, y, thirdW, 14,
                () -> walker.getState() == WaypointWalkerModule.State.PAUSED ? "Resume" : "Pause", false,
                () -> {
                    if (walker.getState() == WaypointWalkerModule.State.PAUSED) {
                        walker.resumeRoute(mc());
                    } else {
                        walker.pauseRoute(mc());
                    }
                }));
        widgets.add(new Button(innerX + (thirdW + 3) * 2, y, thirdW, 14, () -> "Stop", false,
                () -> walker.stopRoute(mc())));
        y += 17;

        // Toggles
        widgets.add(new Toggle(innerX, y, controlsW, "Module Enabled",
                walker::isEnabled, v -> walker.setEnabled(mc(), v)));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Loop Route",
                () -> s.loopRoute, v -> s.loopRoute = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Hold Sprint",
                () -> s.holdSprint, v -> s.holdSprint = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Hold Crouch",
                () -> s.holdCrouch, v -> s.holdCrouch = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Hold L-Click",
                () -> s.holdLeftClick, v -> s.holdLeftClick = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Auto Jump",
                () -> s.autoJump, v -> s.autoJump = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Render Route",
                () -> s.renderWaypoints, v -> s.renderWaypoints = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Micro Rotations",
                () -> s.microRotations, v -> s.microRotations = v));
        y += 15;

        // Sliders
        widgets.add(new Slider(innerX, y, controlsW, "Custom Pitch",
                ConfigManager.MIN_PITCH, ConfigManager.MAX_PITCH,
                () -> s.customPitch, v -> s.customPitch = v,
                v -> String.format("%.0f°", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Waypoint Radius",
                ConfigManager.MIN_RADIUS, ConfigManager.MAX_RADIUS,
                () -> s.waypointRadius, v -> s.waypointRadius = v,
                v -> String.format("%.1f", v)));
        y += 21;

        // Waypoint management
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Add Waypoint", false, this::addWaypointHere));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Remove Nearest", false, this::removeNearest));
        y += 17;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Clear All", true, this::clearAll));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Routes Path", false, this::showRoutesPath));
        y += 17;

        // Route name + JSON import/export
        nameField = new TextField(innerX + 38, y, controlsW - 38, 12,
                () -> BooterClient.config().settings.lastRouteName,
                v -> BooterClient.config().settings.lastRouteName = v, 24, NAME_CHARS);
        widgets.add(nameField);
        y += 15;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Save JSON", false, this::saveRoute));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Load JSON", false, this::loadRoute));
    }

    private void buildMushroomSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = (contentW - pad * 3) * 56 / 100;

        // Waypoint list (shows the loaded mushroom route) on the right.
        listX = innerX + controlsW + pad;
        listW = contentX + contentW - pad - listX;
        listY = panelY + 16 + 15;
        listH = panelY + panelH - pad - listY;

        nameField = null; // this section has no text field

        int halfW = (controlsW - 3) / 2;
        int by = panelY + 16 + 86; // below the description text drawn in renderMushroom
        widgets.add(new Button(innerX, by, controlsW, 16, () -> "Start Mushroom Macro", false, this::startMushroomMacro));
        by += 21;
        widgets.add(new Button(innerX, by, halfW, 14, () -> "Stop", true,
                () -> BooterClient.walker().stopRoute(mc())));
        widgets.add(new Button(innerX + halfW + 3, by, halfW, 14, () -> "Load Route Only", false,
                this::loadMushroomRouteOnly));
    }

    private void buildPathfinderSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = (contentW - pad * 3) * 56 / 100;
        int halfW = (controlsW - 3) / 2;

        // Path node list on the right.
        listX = innerX + controlsW + pad;
        listW = contentX + contentW - pad - listX;
        listY = panelY + 16 + 15;
        listH = panelY + panelH - pad - listY;

        nameField = null;
        ConfigManager.Settings s = BooterClient.config().settings;
        tXBuf = String.valueOf(s.pathTargetX);
        tYBuf = String.valueOf(s.pathTargetY);
        tZBuf = String.valueOf(s.pathTargetZ);

        // Target X / Y / Z fields in a row.
        pfInnerX = innerX;
        pfCellW = (controlsW - 6) / 3;
        pfFieldY = panelY + 16 + 30;
        int fieldX0 = innerX + 9;
        int fieldW = pfCellW - 11;
        widgets.add(new TextField(fieldX0, pfFieldY, fieldW, 12, () -> tXBuf, v -> tXBuf = v, 8, COORD_CHARS));
        widgets.add(new TextField(innerX + pfCellW + 9, pfFieldY, fieldW, 12, () -> tYBuf, v -> tYBuf = v, 8, COORD_CHARS));
        widgets.add(new TextField(innerX + pfCellW * 2 + 9, pfFieldY, fieldW, 12, () -> tZBuf, v -> tZBuf = v, 8, COORD_CHARS));

        int y = pfFieldY + 18;
        widgets.add(new Button(innerX, y, controlsW, 14, () -> "Target = Crosshair", false, this::setTargetCrosshair));
        y += 18;
        widgets.add(new Button(innerX, y, controlsW, 16, () -> "Pathfind", false, this::startPathfind));
        y += 21;
        widgets.add(new Button(innerX, y, controlsW, 16, () -> "Water Pathfind", false, this::startWaterPathfind));
        y += 21;
        widgets.add(new Button(innerX, y, controlsW, 14, () -> "Stop", true,
                () -> BooterClient.pathfinder().stop(mc())));
        y += 18;
        widgets.add(new Toggle(innerX, y, controlsW, "Allow Water (swim)",
                () -> s.pathfindWater, v -> s.pathfindWater = v));
        y += 15;
        widgets.add(new Toggle(innerX, y, halfW, "Hierarchical A*",
                () -> s.hierarchicalPathfinding, v -> s.hierarchicalPathfinding = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Hierarchy Debug",
                () -> s.hierarchyDebug, v -> s.hierarchyDebug = v));
        y += 15;
        widgets.add(new Slider(innerX, y, controlsW, "Hierarchy Min Distance", 16.0f, 512.0f,
                () -> (float) s.hierarchyMinDistance, v -> s.hierarchyMinDistance = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Waypoint Search Radius", 32.0f, 2048.0f,
                () -> (float) s.hierarchyWaypointSearchRadius, v -> s.hierarchyWaypointSearchRadius = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Max Local Segment", 16.0f, 256.0f,
                () -> (float) s.hierarchyMaxLocalDistance, v -> s.hierarchyMaxLocalDistance = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Edge Block Timeout", 5.0f, 300.0f,
                () -> (float) s.hierarchyEdgeInvalidSeconds, v -> s.hierarchyEdgeInvalidSeconds = Math.round(v),
                v -> String.format("%.0fs", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Route Cache Lifetime", 10.0f, 1800.0f,
                () -> (float) s.hierarchyCacheLifetimeSeconds, v -> s.hierarchyCacheLifetimeSeconds = Math.round(v),
                v -> String.format("%.0fs", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
    }

    private void buildMushroomFarmerSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int halfW = (controlsW - 3) / 2;
        int y = panelY + 16 + 64; // below the description text drawn in render
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.mushroomFarmer().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.mushroomFarmer().stop(mc())));
        y += 22;
        widgets.add(new Slider(innerX, y, controlsW, "Look Time", 0.5f, 10.0f,
                () -> s.mushroomLookSeconds, v -> s.mushroomLookSeconds = v,
                v -> String.format("%.2fs", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Scan Radius", 4.0f, 48.0f,
                () -> (float) s.mushroomScanRadius, v -> s.mushroomScanRadius = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
    }

    private void buildBlockMinerSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;

        int y = panelY + 16 + 26; // below the status + debug lines
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Start", false,
                () -> BooterClient.blockMiner().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Stop", true,
                () -> BooterClient.blockMiner().stop(mc())));
        y += 18;

        // Block-type toggles (two columns).
        widgets.add(new Toggle(innerX, y, halfW, "Diamond Block", () -> s.mineDiamondBlock, v -> s.mineDiamondBlock = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Iron Block", () -> s.mineIronBlock, v -> s.mineIronBlock = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Emerald Block", () -> s.mineEmeraldBlock, v -> s.mineEmeraldBlock = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Blue Wool", () -> s.mineBlueWool, v -> s.mineBlueWool = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Gray Wool", () -> s.mineGrayWool, v -> s.mineGrayWool = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Gray Terracotta", () -> s.mineGrayTerracotta, v -> s.mineGrayTerracotta = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Quartz Block", () -> s.mineQuartzBlock, v -> s.mineQuartzBlock = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Prismarine", () -> s.minePrismarine, v -> s.minePrismarine = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Dark Prismarine", () -> s.mineDarkPrismarine, v -> s.mineDarkPrismarine = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Prismarine Bricks", () -> s.minePrismarineBricks, v -> s.minePrismarineBricks = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Logs (all trees)", () -> s.mineLogs, v -> s.mineLogs = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Coal Block", () -> s.mineCoalBlock, v -> s.mineCoalBlock = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Glass (all + panes)", () -> s.mineGlass, v -> s.mineGlass = v));
        y += 14;
        widgets.add(new Toggle(innerX, y, halfW, "Use Route", () -> s.minerUseRoute, v -> s.minerUseRoute = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Crouch", () -> s.minerCrouch, v -> s.minerCrouch = v));
        y += 14;

        // Tunnel (strip-mine) pattern: straight parallel tunnels, 1-block wall between.
        widgets.add(new Toggle(innerX, y, controlsW, "Tunnel Mode (strip mine)",
                () -> s.minerTunnelMode, v -> s.minerTunnelMode = v));
        y += 15;
        widgets.add(new Slider(innerX, y, controlsW, "Tunnel Length", 1.0f, 64.0f,
                () -> (float) s.minerTunnelLength, v -> s.minerTunnelLength = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Tunnel Count", 1.0f, 16.0f,
                () -> (float) s.minerTunnelCount, v -> s.minerTunnelCount = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Tunnel Height", 2.0f, 4.0f,
                () -> (float) s.minerTunnelHeight, v -> s.minerTunnelHeight = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Toggle(innerX, y, controlsW, "Mine Exposed Ores",
                () -> s.minerTunnelMineOres, v -> s.minerTunnelMineOres = v));
        y += 15;

        widgets.add(new Slider(innerX, y, controlsW, "Mining Range", 2.0f, 6.0f,
                () -> s.minerRange, v -> s.minerRange = v, v -> String.format("%.1f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Scan Radius", 4.0f, 64.0f,
                () -> (float) s.minerScanRadius, v -> s.minerScanRadius = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Scan Interval", 1.0f, 40.0f,
                () -> (float) s.minerScanInterval, v -> s.minerScanInterval = Math.round(v),
                v -> String.format("%.0ft", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Switch Delay", 0.0f, 100.0f,
                () -> (float) s.minerSwitchDelay, v -> s.minerSwitchDelay = Math.round(v),
                v -> String.format("%.0ft", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Mine Timeout", 20.0f, 400.0f,
                () -> (float) s.minerMineTimeout, v -> s.minerMineTimeout = Math.round(v),
                v -> String.format("%.1fs", v / 20.0f)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
        y += 22;

        // ---- Route management (shared waypoints; multiple named routes) ----
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Add Waypoint", false, this::addWaypointHere));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Clear Route", true, this::clearAll));
        y += 17;
        nameField = new TextField(innerX + 38, y, controlsW - 38, 12,
                () -> BooterClient.config().settings.lastRouteName,
                v -> BooterClient.config().settings.lastRouteName = v, 24, NAME_CHARS);
        widgets.add(nameField);
        y += 15;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Save Route", false, this::saveRouteAndRefresh));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Load Route", false, this::loadRoute));
        y += 17;
        minerRoutesLabelY = y;
        y += 11;
        for (String routeName : cachedRoutes) {
            widgets.add(new Button(innerX, y, controlsW, 12, () -> "Load: " + routeName, false,
                    () -> loadNamedRoute(routeName)));
            y += 13;
        }
    }

    private void buildPathDebugSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        com.booter.client.pathdebug.PathDebugSettings s = com.booter.client.pathdebug.PathDebugManager.get().settings;
        nameField = null;

        int y = panelY + 16 + 44; // below the status + stats lines drawn in renderPathDebug

        widgets.add(new Toggle(innerX, y, controlsW, "Visualizer Enabled",
                () -> s.enabled, v -> s.enabled = v));
        y += 14;
        widgets.add(new Toggle(innerX, y, halfW, "Explored (red)",
                () -> s.renderExploredNodes, v -> s.renderExploredNodes = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Open (yellow)",
                () -> s.renderOpenNodes, v -> s.renderOpenNodes = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Final Path (green)",
                () -> s.renderFinalPath, v -> s.renderFinalPath = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Destination",
                () -> s.renderDestination, v -> s.renderDestination = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, controlsW, "Show Statistics Overlay",
                () -> s.showPathStatistics, v -> s.showPathStatistics = v));
        y += 16;

        widgets.add(new Slider(innerX, y, controlsW, "Line Thickness", 1.0f, 16.0f,
                () -> s.lineThickness, v -> s.lineThickness = v, v -> String.format("%.1f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Node Size", 0.05f, 0.5f,
                () -> s.nodeSize, v -> s.nodeSize = v, v -> String.format("%.2f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Node Opacity", 0.1f, 1.0f,
                () -> s.nodeOpacity, v -> s.nodeOpacity = v, v -> String.format("%.0f%%", v * 100.0f)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Max Render Distance", 16.0f, 256.0f,
                () -> (float) s.maxRenderDistance, v -> s.maxRenderDistance = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 21;

        widgets.add(new Button(innerX, y, controlsW, 14, () -> "Cycle Line Colour", false, () -> {
            int idx = 0;
            for (int i = 0; i < DEBUG_LINE_COLORS.length; i++) {
                if (DEBUG_LINE_COLORS[i] == s.lineColor) {
                    idx = i + 1;
                    break;
                }
            }
            s.lineColor = DEBUG_LINE_COLORS[idx % DEBUG_LINE_COLORS.length];
        }));
        y += 17;
        widgets.add(new Button(innerX, y, controlsW, 14, () -> "Clear Visualization", true, () -> {
            com.booter.client.pathdebug.PathDebugManager m = com.booter.client.pathdebug.PathDebugManager.get();
            m.clearNodes();
            m.clearPath();
            m.setTarget(null);
        }));
    }

    private void buildZealotEmanSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        nameField = null;

        int y = panelY + 16 + 64;
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.zealotEmanFarmer().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.zealotEmanFarmer().stop(mc())));
    }

    private void buildDevModeSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = (contentW - pad * 3) * 56 / 100;

        listX = innerX + controlsW + pad;
        listW = contentX + contentW - pad - listX;
        listY = panelY + 16 + 15;
        listH = panelY + panelH - pad - listY;

        int halfW = (controlsW - 3) / 2;
        int y = panelY + 16 + 64;

        widgets.add(new Button(innerX, y, halfW, 14, () -> "Walk: Player Pos", false,
                () -> addWaypointHere(Waypoint.Type.WALKING)));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Arrive: Player Pos", false,
                () -> addWaypointHere(Waypoint.Type.ARRIVED)));
        y += 17;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Walk: Look Block", false,
                () -> addLookedBlockWaypoint(Waypoint.Type.WALKING)));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Arrive: Look Block", false,
                () -> addLookedBlockWaypoint(Waypoint.Type.ARRIVED)));
        y += 17;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Remove Nearest", false, this::removeNearest));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Clear Dev Route", true, this::clearAll));
        y += 18;

        nameField = new TextField(innerX + 38, y, controlsW - 38, 12,
                () -> BooterClient.config().settings.lastRouteName,
                v -> BooterClient.config().settings.lastRouteName = v, 24, NAME_CHARS);
        widgets.add(nameField);
        y += 15;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Save JSON", false, this::saveRouteAndRefresh));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Load JSON", false, this::loadRoute));
        y += 17;
        widgets.add(new Button(innerX, y, controlsW, 14, () -> "Export Java Snippet", false, this::exportJavaSnippet));
        y += 17;
        widgets.add(new Button(innerX, y, controlsW, 14, () -> "Show Routes Folder", false, this::showRoutesPath));
    }

    private void buildMovementRecorderSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;

        int y = panelY + 16 + 60;
        widgets.add(new Button(innerX, y, halfW, 16,
                () -> BooterClient.movementRecorder().isRecording() ? "Stop Recording" : "Start Recording",
                false, () -> BooterClient.movementRecorder().toggle(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Add END Node", false,
                () -> BooterClient.movementRecorder().addEndNode(mc())));
        y += 21;
        widgets.add(new Button(innerX, y, halfW, 14, () -> "Clear Recording", true,
                () -> BooterClient.movementRecorder().clear()));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 14, () -> "Save JSON", false,
                () -> BooterClient.movementRecorder().save(BooterClient.config().settings.lastRecordingName)));
        y += 18;
        widgets.add(new Slider(innerX, y, controlsW, "Node Spacing", 0.25f, 5.0f,
                () -> s.recorderNodeSpacing, v -> s.recorderNodeSpacing = v,
                v -> String.format("%.2f", v)));
        y += 21;
        nameField = new TextField(innerX + 55, y, controlsW - 55, 12,
                () -> BooterClient.config().settings.lastRecordingName,
                v -> BooterClient.config().settings.lastRecordingName = v, 32, NAME_CHARS);
        widgets.add(nameField);
    }

    private void selectSection(int index) {
        if (index != selectedSection && index >= 0 && index < SECTIONS.length) {
            selectedSection = index;
            contentScroll = 0;
            refreshRoutes();
            playClick();
            rebuild();
        }
    }

    private void saveRouteAndRefresh() {
        saveRoute();
        refreshRoutes();
        rebuild();
    }

    private void loadNamedRoute(String name) {
        BooterClient.config().settings.lastRouteName = name;
        int count = BooterClient.waypoints().loadRoute(name);
        if (count < 0) {
            BooterClient.chat("Failed to load route '" + name + "'.");
        } else {
            BooterClient.chat("Loaded route '" + name + "': " + count + " waypoint(s).");
            BooterClient.walker().onWaypointsMutated(mc());
        }
    }

    /** Renders the settings widgets clipped to the scrollable viewport, with a scrollbar. */
    private void renderWidgetsScrolled(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.enableScissor(contentX, contentViewTop, panelX + panelW, contentViewBottom);
        for (Widget widget : widgets) {
            widget.render(g, mouseX, mouseY);
        }
        g.disableScissor();
        if (contentMax > 0) {
            int viewportH = contentViewBottom - contentViewTop;
            int contentH = viewportH + (int) contentMax;
            int barH = Math.max(8, viewportH * viewportH / contentH);
            int barY = contentViewTop + (int) ((viewportH - barH) * (contentScroll / contentMax));
            int barX = panelX + panelW - 3;
            g.fill(barX, barY, barX + 2, barY + barH, COL_ACCENT);
        }
    }

    private boolean inContentViewport(double my) {
        return my >= contentViewTop && my <= contentViewBottom;
    }

    // ------------------------------------------------------------------ actions

    private void addWaypointHere() {
        addWaypointHere(Waypoint.Type.ARRIVED);
    }

    private void addWaypointHere(Waypoint.Type type) {
        var player = mc().player;
        if (player == null) {
            return;
        }
        int number = BooterClient.waypoints().add(player.getX(), player.getY(), player.getZ(), type);
        BooterClient.chat(String.format("Added %s waypoint #%d at (%.1f, %.1f, %.1f).",
                waypointTypeLabel(type), number, player.getX(), player.getY(), player.getZ()));
    }

    private void addLookedBlockWaypoint() {
        addLookedBlockWaypoint(Waypoint.Type.ARRIVED);
    }

    private void addLookedBlockWaypoint(Waypoint.Type type) {
        Minecraft m = mc();
        if (m.hitResult == null || m.hitResult.getType() != HitResult.Type.BLOCK) {
            BooterClient.chat("Look at a block to add it as a dev waypoint.");
            return;
        }
        BlockPos p = ((BlockHitResult) m.hitResult).getBlockPos();
        int number = BooterClient.waypoints().add(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, type);
        BooterClient.chat("Added " + waypointTypeLabel(type) + " waypoint #" + number
                + " at block " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ".");
    }

    private void removeNearest() {
        var player = mc().player;
        if (player == null) {
            return;
        }
        double maxDistance = BooterClient.config().settings.removeDistance;
        int removed = BooterClient.waypoints().removeNearest(
                player.getX(), player.getY(), player.getZ(), maxDistance);
        if (removed < 0) {
            BooterClient.chat(String.format("No waypoint within %.0f blocks.", maxDistance));
        } else {
            BooterClient.chat("Removed waypoint #" + removed + ".");
            BooterClient.walker().onWaypointsMutated(mc());
        }
    }

    private void clearAll() {
        int cleared = BooterClient.waypoints().clear();
        BooterClient.chat("Cleared " + cleared + " waypoint(s).");
        BooterClient.walker().onWaypointsMutated(mc());
    }

    private void showRoutesPath() {
        BooterClient.chat("Routes folder: " + BooterClient.config().routesDir().toAbsolutePath());
    }

    private void saveRoute() {
        String file = BooterClient.waypoints().saveRoute(BooterClient.config().settings.lastRouteName);
        BooterClient.chat(file != null
                ? "Route exported to config/booterclient/routes/" + file
                : "Failed to export route (see log).");
    }

    private void exportJavaSnippet() {
        List<Waypoint> list = BooterClient.waypoints().view();
        if (list.isEmpty()) {
            BooterClient.chat("No waypoints to export.");
            return;
        }
        String baseName = sanitizeRouteName(BooterClient.config().settings.lastRouteName);
        Path file = BooterClient.config().routesDir().resolve(baseName + "_hardcoded.java.txt");
        StringBuilder out = new StringBuilder();
        out.append("List.of(\n");
        for (int i = 0; i < list.size(); i++) {
            Waypoint wp = list.get(i);
            out.append(String.format(Locale.ROOT, "        new Waypoint(%.6f, %.6f, %.6f, Waypoint.Type.%s)",
                    wp.x, wp.y, wp.z, wp.type == null ? Waypoint.Type.ARRIVED : wp.type));
            out.append(i + 1 == list.size() ? "\n" : ",\n");
        }
        out.append(");\n");
        try {
            Files.writeString(file, out.toString());
            BooterClient.chat("Exported hardcode snippet to config/booterclient/routes/" + file.getFileName() + ".");
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to export dev route snippet", e);
            BooterClient.chat("Failed to export hardcode snippet.");
        }
    }

    private void loadRoute() {
        String name = BooterClient.config().settings.lastRouteName;
        int count = BooterClient.waypoints().loadRoute(name);
        if (count < 0) {
            BooterClient.chat("No readable route named '" + name + ".json' in the routes folder.");
        } else {
            BooterClient.chat("Imported " + count + " waypoint(s) from '" + name + ".json'.");
            BooterClient.walker().onWaypointsMutated(mc());
        }
    }

    /**
     * Mushroom Macro preset: load the hardcoded route bundled in the jar and
     * force auto-jump, a locked 30° pitch and hold-sprint, then start walking
     * (looping). Overwrites the current waypoint list with the macro route.
     */
    private void startMushroomMacro() {
        ConfigManager.Settings s = BooterClient.config().settings;
        int n = BooterClient.waypoints().loadBundledRoute("mushroom");
        if (n < 0) {
            BooterClient.chat("Failed to load the bundled mushroom route.");
            return;
        }
        s.autoJump = true;
        s.holdSprint = true;
        s.customPitch = 30.0f;
        s.loopRoute = true;
        BooterClient.config().clamp();
        BooterClient.walker().setEnabled(mc(), true);
        BooterClient.walker().onWaypointsMutated(mc());
        BooterClient.walker().startRoute(mc());
        BooterClient.chat("Mushroom Macro started: " + n + " waypoints, auto-jump on, pitch 30°, sprint on, looping.");
    }

    private void loadMushroomRouteOnly() {
        int n = BooterClient.waypoints().loadBundledRoute("mushroom");
        if (n < 0) {
            BooterClient.chat("Failed to load the bundled mushroom route.");
            return;
        }
        BooterClient.walker().onWaypointsMutated(mc());
        BooterClient.chat("Loaded mushroom route: " + n + " waypoints (settings unchanged).");
    }

    private void startPathfind() {
        ConfigManager.Settings s = BooterClient.config().settings;
        s.pathTargetX = parseInt(tXBuf, s.pathTargetX);
        s.pathTargetY = parseInt(tYBuf, s.pathTargetY);
        s.pathTargetZ = parseInt(tZBuf, s.pathTargetZ);
        tXBuf = String.valueOf(s.pathTargetX);
        tYBuf = String.valueOf(s.pathTargetY);
        tZBuf = String.valueOf(s.pathTargetZ);
        BooterClient.pathfinder().start(mc(), s.pathTargetX, s.pathTargetY, s.pathTargetZ);
    }

    private void startWaterPathfind() {
        ConfigManager.Settings s = BooterClient.config().settings;
        s.pathTargetX = parseInt(tXBuf, s.pathTargetX);
        s.pathTargetY = parseInt(tYBuf, s.pathTargetY);
        s.pathTargetZ = parseInt(tZBuf, s.pathTargetZ);
        tXBuf = String.valueOf(s.pathTargetX);
        tYBuf = String.valueOf(s.pathTargetY);
        tZBuf = String.valueOf(s.pathTargetZ);
        BooterClient.pathfinder().startWater(mc(), s.pathTargetX, s.pathTargetY, s.pathTargetZ);
    }

    private void setTargetCrosshair() {
        Minecraft m = mc();
        if (m.hitResult != null && m.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos p = ((BlockHitResult) m.hitResult).getBlockPos();
            ConfigManager.Settings s = BooterClient.config().settings;
            s.pathTargetX = p.getX();
            s.pathTargetY = p.getY();
            s.pathTargetZ = p.getZ();
            tXBuf = String.valueOf(p.getX());
            tYBuf = String.valueOf(p.getY());
            tZBuf = String.valueOf(p.getZ());
            BooterClient.chat("Pathfinder target set to " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ".");
        } else {
            BooterClient.chat("Look at a block to set the pathfinder target.");
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        Font font = font();

        // Panel + header
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);
        g.outline(panelX, panelY, panelW, panelH, COL_BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 16, COL_HEADER);
        g.fill(panelX, panelY + 15, panelX + panelW, panelY + 16, COL_ACCENT);
        g.text(font, "Booter Client", panelX + 6, panelY + 4, COL_TEXT, false);
        String sectionName = SECTIONS[selectedSection];
        g.text(font, sectionName, panelX + panelW - 6 - font.width(sectionName), panelY + 4, COL_ACCENT, false);

        // Sidebar
        g.fill(panelX, panelY + 16, contentX, panelY + panelH, COL_SIDEBAR);
        g.fill(contentX - 1, panelY + 16, contentX, panelY + panelH, COL_BORDER);
        for (int i = 0; i < SECTIONS.length; i++) {
            int ty = tabY0 + i * tabGap;
            boolean selected = i == selectedSection;
            boolean hovered = mouseX >= tabX && mouseX < tabX + tabW && mouseY >= ty && mouseY < ty + tabH;
            g.fill(tabX, ty, tabX + tabW, ty + tabH, selected ? COL_TAB_SELECTED : (hovered ? COL_BUTTON_HOVER : COL_TAB));
            if (selected) {
                g.fill(tabX, ty, tabX + 2, ty + tabH, COL_ACCENT);
            }
            g.text(font, SECTIONS[i], tabX + 6, ty + (tabH - 8) / 2, selected ? 0xFFFFFFFF : COL_DIM, false);
        }

        // Content
        if (selectedSection == 0) {
            renderRouteWalker(g, mouseX, mouseY);
        } else if (selectedSection == 1) {
            renderMushroom(g, mouseX, mouseY);
        } else if (selectedSection == 2) {
            renderPathfinder(g, mouseX, mouseY);
        } else if (selectedSection == 3) {
            renderMushroomFarmer(g, mouseX, mouseY);
        } else if (selectedSection == 4) {
            renderBlockMiner(g, mouseX, mouseY);
        } else if (selectedSection == 5) {
            renderAutoFisher(g, mouseX, mouseY);
        } else if (selectedSection == 6) {
            renderFishHunter(g, mouseX, mouseY);
        } else if (selectedSection == 7) {
            renderTurtleHunter(g, mouseX, mouseY);
        } else if (selectedSection == 8) {
            renderCombat(g, mouseX, mouseY);
        } else if (selectedSection == 9) {
            renderCoalMiner(g, mouseX, mouseY);
        } else if (selectedSection == 10) {
            renderAutoFarmer(g, mouseX, mouseY);
        } else if (selectedSection == 11) {
            renderAzaleaFarmer(g, mouseX, mouseY);
        } else if (selectedSection == 12) {
            renderZealotEman(g, mouseX, mouseY);
        } else if (selectedSection == 13) {
            renderPathDebug(g, mouseX, mouseY);
        } else if (selectedSection == 14) {
            renderDevMode(g, mouseX, mouseY);
        } else if (selectedSection == 15) {
            renderMovementRecorder(g, mouseX, mouseY);
        }
    }

    private void buildAutoFarmerSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;

        int y = panelY + 16 + 44; // below the description + route field label
        // Route name to walk (default "plot4").
        nameField = new TextField(innerX + 38, y, controlsW - 38, 12,
                () -> s.farmerRoute, v -> s.farmerRoute = v, 24, NAME_CHARS);
        widgets.add(nameField);
        y += 16;
        widgets.add(new Toggle(innerX, y, halfW, "Farm (else walk)",
                () -> s.farmerFarm, v -> s.farmerFarm = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "All Crop Types",
                () -> s.farmerAllCrops, v -> s.farmerAllCrops = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Hold Crouch",
                () -> s.farmerCrouch, v -> s.farmerCrouch = v));
        y += 16;
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.autoFarmer().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.autoFarmer().stop(mc())));
    }

    private void renderAutoFarmer(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var farmer = BooterClient.autoFarmer();
        boolean active = farmer.getState() != com.booter.client.farmer.AutoFarmerModule.State.IDLE;
        String status = active
                ? "Farming (wp " + (farmer.getCurrentIndex() + 1) + "/" + BooterClient.waypoints().size() + ")"
                : "Idle";
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, active ? COL_ACTIVE : COL_DIM, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 18;
        g.text(font, "Walks a saved route on a loop, harvesting", dx, dy, COL_TEXT, false);
        g.text(font, "grown crops in front. Toggle farm/walk.", dx, dy + 11, COL_DIM, false);

        // Route field label (drawn next to the text field it clips with).
        if (nameField != null && inContentViewport(nameField.y)) {
            g.enableScissor(contentX, contentViewTop, panelX + panelW, contentViewBottom);
            g.text(font, "Route:", nameField.x - 36, nameField.y + 2, COL_DIM, false);
            g.disableScissor();
        }

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void buildCoalMinerSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int y = panelY + 16 + 56; // below the description text
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.coalMiner().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.coalMiner().stop(mc())));
        y += 22;
        widgets.add(new Slider(innerX, y, controlsW, "Scan Radius", 8.0f, 96.0f,
                () -> (float) s.coalScanRadius, v -> s.coalScanRadius = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Mining Range", 2.0f, 6.0f,
                () -> s.minerRange, v -> s.minerRange = v, v -> String.format("%.1f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Mine Tool Slot", 1.0f, 9.0f,
                () -> (float) s.coalMineSlot, v -> s.coalMineSlot = Math.round(v),
                v -> String.format("%.0f", v)));
    }

    private void renderCoalMiner(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var cm = BooterClient.coalMiner();
        String status = switch (cm.getState()) {
            case IDLE -> "Idle";
            case SEEKING -> "Scanning for coal…";
            case WALKING -> "Approaching vein";
            case DIGGING -> "Digging to coal";
            case MINING -> "Mining coal";
        };
        boolean active = cm.getState() != com.booter.client.coal.CoalMinerModule.State.IDLE;
        if (active && cm.getVeinSize() > 0) {
            status += " (vein " + cm.getVeinSize() + ")";
        }
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, active ? COL_ACTIVE : COL_DIM, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 18;
        g.text(font, "Finds coal veins (even buried in stone),", dx, dy, COL_TEXT, false);
        g.text(font, "tunnels through to them and mines them,", dx, dy + 11, COL_DIM, false);
        g.text(font, "then moves on to the next. On your world.", dx, dy + 22, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void buildAutoFisherSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int y = panelY + 16 + 60; // below the description text drawn in renderAutoFisher
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.autoFisher().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.autoFisher().stop(mc())));
        y += 22;
        widgets.add(new Toggle(innerX, y, controlsW, "Advanced Fisher",
                () -> s.fisherAdvanced, v -> s.fisherAdvanced = v));
        y += 15;
        widgets.add(new Slider(innerX, y, controlsW, "Catch Target", 1.0f, 20.0f,
                () -> (float) s.fisherCatchTarget, v -> s.fisherCatchTarget = Math.round(v),
                v -> String.format("%.0f", v)));
    }

    private void renderAutoFisher(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var fisher = BooterClient.autoFisher();
        boolean fishing = fisher.getState() == com.booter.client.fisher.AutoFisherModule.State.FISHING;
        ConfigManager.Settings s = BooterClient.config().settings;
        String status = fishing ? "Fishing" : "Idle";
        if (fishing && s.fisherAdvanced) {
            status += " (" + fisher.getCatchCount() + "/" + s.fisherCatchTarget + ")";
        }
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, fishing ? COL_ACTIVE : COL_DIM, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 18;
        g.text(font, "Hold a fishing rod, aim at water, Start.", dx, dy, COL_TEXT, false);
        g.text(font, "Casts where you aim, locks the view,", dx, dy + 11, COL_DIM, false);
        g.text(font, "reels catches and recasts. Advanced: after", dx, dy + 22, COL_DIM, false);
        g.text(font, "N catches, look down + right-click once.", dx, dy + 33, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void buildFishHunterSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int y = panelY + 16 + 58; // below the status + description text
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.fishHunter().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.fishHunter().stop(mc())));
        y += 22;
        widgets.add(new Slider(innerX, y, controlsW, "Attack Distance", 2.0f, 8.0f,
                () -> s.fishHunterDistance, v -> s.fishHunterDistance = v,
                v -> String.format("%.1f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Attack Slot", 1.0f, 8.0f,
                () -> (float) s.fishHunterSlot, v -> s.fishHunterSlot = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
    }

    private void renderFishHunter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var hunter = BooterClient.fishHunter();
        String status = switch (hunter.getState()) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning for fish…";
            case PATHING_WATER -> "Swimming to fish";
            case PATHING_LAND -> "Walking to fish";
            case BACKING_OFF -> "Backing away";
            case ATTACKING -> "Attacking fish";
        };
        int statusColor = hunter.getState() == com.booter.client.fishhunter.FishHunterModule.State.IDLE
                ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        var target = hunter.getTarget();
        String t = target == null ? "(none)" : target.getType().toShortString()
                + " " + String.format("%.1fm", Math.sqrt(mc().player == null ? 0.0 : mc().player.distanceToSqr(target)));
        g.text(font, "Target: " + t, contentX + 6, panelY + 32, COL_TEXT, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 30;
        g.text(font, "Finds cod, salmon and tropical fish,", dx, dy, COL_DIM, false);
        g.text(font, "prefers water paths, falls back to walking,", dx, dy + 11, COL_DIM, false);
        g.text(font, "then aims and holds left click in range.", dx, dy + 22, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void buildTurtleHunterSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int y = panelY + 16 + 58; // below the status + description text
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.turtleHunter().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.turtleHunter().stop(mc())));
        y += 22;
        widgets.add(new Slider(innerX, y, controlsW, "Interact Distance", 2.0f, 8.0f,
                () -> s.turtleHunterDistance, v -> s.turtleHunterDistance = v,
                v -> String.format("%.1f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Use Slot", 1.0f, 8.0f,
                () -> (float) s.turtleHunterSlot, v -> s.turtleHunterSlot = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Toggle(innerX, y, controlsW, "Right Click",
                () -> s.turtleHunterRightClick, v -> s.turtleHunterRightClick = v));
        y += 15;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
    }

    private void renderTurtleHunter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var hunter = BooterClient.turtleHunter();
        String status = switch (hunter.getState()) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning for turtles…";
            case PATHING_WATER -> "Swimming to turtle";
            case PATHING_LAND -> "Walking to turtle";
            case RETURNING -> "Following return route";
            case BACKING_OFF -> "Backing away";
            case ATTACKING -> "Interacting with turtle";
        };
        int statusColor = hunter.getState() == com.booter.client.turtlehunter.TurtleHunterModule.State.IDLE
                ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        var target = hunter.getTarget();
        String t = target == null ? "(none)" : target.getType().toShortString()
                + " " + String.format("%.1fm", Math.sqrt(mc().player == null ? 0.0 : mc().player.distanceToSqr(target)));
        g.text(font, "Target: " + t, contentX + 6, panelY + 32, COL_TEXT, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 30;
        g.text(font, "Finds turtles only, prefers water paths,", dx, dy, COL_DIM, false);
        g.text(font, "keeps two blocks away, then aims and", dx, dy + 11, COL_DIM, false);
        g.text(font, "right-clicks while the target is in view.", dx, dy + 22, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void buildCombatSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int y = panelY + 16 + 44; // below status + target
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.combat().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.combat().stop(mc())));
        y += 21;
        widgets.add(new Toggle(innerX, y, controlsW, "One Tap Mode",
                () -> s.combatOneTap, v -> s.combatOneTap = v));
        y += 15;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
        y += 21;

        widgets.add(new Toggle(innerX, y, halfW, "Charged Creeper", () -> s.combatChargedCreeper, v -> s.combatChargedCreeper = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Creeper", () -> s.combatCreeper, v -> s.combatCreeper = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Zombie/Husk/Drowned", () -> s.combatZombie, v -> s.combatZombie = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Skeleton/Stray/Bogged", () -> s.combatSkeleton, v -> s.combatSkeleton = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Spider/Cave Spider", () -> s.combatSpider, v -> s.combatSpider = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Enderman", () -> s.combatEnderman, v -> s.combatEnderman = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Witch", () -> s.combatWitch, v -> s.combatWitch = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Slime/Magma Cube", () -> s.combatSlime, v -> s.combatSlime = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Nether Mobs", () -> s.combatNetherMobs, v -> s.combatNetherMobs = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Illagers", () -> s.combatIllagers, v -> s.combatIllagers = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Guardians", () -> s.combatGuardians, v -> s.combatGuardians = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Silverfish/Endermite", () -> s.combatArthropods, v -> s.combatArthropods = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Warden/Breeze", () -> s.combatWardenBreeze, v -> s.combatWardenBreeze = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Animals", () -> s.combatAnimals, v -> s.combatAnimals = v));
        y += 13;
        widgets.add(new Toggle(innerX, y, halfW, "Aquatic Mobs", () -> s.combatAquatic, v -> s.combatAquatic = v));
        widgets.add(new Toggle(innerX + halfW + 3, y, halfW, "Villagers/Golems", () -> s.combatVillagersGolems, v -> s.combatVillagersGolems = v));
    }

    private void renderCombat(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var combat = BooterClient.combat();
        String status = switch (combat.getState()) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning for mobs…";
            case PATHING_WATER -> "Swimming to mob";
            case PATHING_LAND -> "Walking to mob";
            case ATTACKING -> "Attacking mob";
        };
        int statusColor = combat.getState() == com.booter.client.combat.CombatModule.State.IDLE
                ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        var target = combat.getTarget();
        String t = target == null ? "(none)" : target.getType().toShortString()
                + " " + String.format("%.1fm", Math.sqrt(mc().player == null ? 0.0 : mc().player.distanceToSqr(target)));
        g.text(font, "Target: " + t, contentX + 6, panelY + 32, COL_TEXT, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void buildAzaleaFarmerSection() {
        int pad = 6;
        int innerX = contentX + pad;
        int controlsW = contentW - pad * 2;
        int halfW = (controlsW - 3) / 2;
        ConfigManager.Settings s = BooterClient.config().settings;
        nameField = null;

        int y = panelY + 16 + 56;
        widgets.add(new Button(innerX, y, halfW, 16, () -> "Start", false,
                () -> BooterClient.azaleaFarmer().start(mc())));
        widgets.add(new Button(innerX + halfW + 3, y, halfW, 16, () -> "Stop", true,
                () -> BooterClient.azaleaFarmer().stop(mc())));
        y += 22;
        widgets.add(new Slider(innerX, y, controlsW, "Scan Radius", 4.0f, 64.0f,
                () -> (float) s.azaleaScanRadius, v -> s.azaleaScanRadius = Math.round(v),
                v -> String.format("%.0f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Scan Interval", 1.0f, 40.0f,
                () -> (float) s.azaleaScanInterval, v -> s.azaleaScanInterval = Math.round(v),
                v -> String.format("%.0ft", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Mining Range", 2.0f, 6.0f,
                () -> s.azaleaMineRange, v -> s.azaleaMineRange = v,
                v -> String.format("%.1f", v)));
        y += 19;
        widgets.add(new Slider(innerX, y, controlsW, "Rotation Multiplier",
                ConfigManager.MIN_ROTATION_MULTIPLIER, ConfigManager.MAX_ROTATION_MULTIPLIER,
                () -> s.rotationMultiplier, v -> s.rotationMultiplier = v,
                v -> String.format("%.2f", v)));
    }

    private void renderAzaleaFarmer(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var farmer = BooterClient.azaleaFarmer();
        String status = switch (farmer.getState()) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning flowering azaleas…";
            case WALKING -> "Walking to flowering azalea";
            case ROUTING -> "Following azalea route";
            case ROTATING -> "Rotating to flowering azalea";
            case MINING -> "Breaking flowering azalea";
        };
        int statusColor = farmer.getState() == com.booter.client.azalea.FloweringAzaleaFarmerModule.State.IDLE
                ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);
        var target = farmer.getTarget();
        String t = target == null ? "(none)" : target.getX() + ", " + target.getY() + ", " + target.getZ();
        g.text(font, "Target: " + t + " · queued " + farmer.queuedCount(), contentX + 6, panelY + 32, COL_TEXT, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 30;
        g.text(font, "Scans flowering azalea blocks into a queue,", dx, dy, COL_DIM, false);
        g.text(font, "pre-aims on approach and breaks each block,", dx, dy + 11, COL_DIM, false);
        g.text(font, "then moves to the next still-flowering target.", dx, dy + 22, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void renderPathDebug(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        com.booter.client.pathdebug.PathDebugManager m = com.booter.client.pathdebug.PathDebugManager.get();
        com.booter.client.pathdebug.PathDebugSettings s = m.settings;

        String status = s.enabled ? "Active" : "Disabled";
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, s.enabled ? COL_ACTIVE : COL_DIM, false);

        // Live search statistics (mirror the in-world overlay).
        String stats = String.format("Explored %d · Open %d · Path %d", m.nodesExplored(), m.openCount(), m.pathLength());
        g.text(font, stats, contentX + 6, panelY + 31, COL_DIM, false);
        String timing = String.format("Compute %d ms · Render %.2f ms", m.computeTimeMs(), m.avgRenderMs());
        g.text(font, timing, contentX + 6, panelY + 42, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void renderZealotEman(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var farmer = BooterClient.zealotEmanFarmer();
        String status = switch (farmer.getState()) {
            case IDLE -> "Idle";
            case SCANNING -> "Scanning for eligible endermen";
            case PATHING -> "Pathing to enderman";
            case ATTACKING -> "Attacking enderman";
        };
        int statusColor = farmer.getState() == com.booter.client.zealot.ZealotEmanFarmerModule.State.IDLE ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);
        var target = farmer.getTarget();
        String t = target == null ? "(none)" : String.format(Locale.ROOT, "%.1f %.1f %.1f", target.getX(), target.getY(), target.getZ());
        g.text(font, "Target: " + t + " · hardcoded nodes " + farmer.nodeCount(), contentX + 6, panelY + 32, COL_TEXT, false);
        var node = farmer.getTargetNode();
        String n = node == null ? "(none)" : node.name + " " + node.x + " " + node.y + " " + node.z;
        g.text(font, "Nearest node: " + n, contentX + 6, panelY + 44, COL_DIM, false);
        g.text(font, "Only attacks endermen within 4.5 blocks of a node.", contentX + 6, panelY + 56, COL_DIM, false);
        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void renderDevMode(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        g.text(font, "Capture waypoints for hardcoded movement routes.", contentX + 6, panelY + 20, COL_TEXT, false);
        g.text(font, "WALKING = path through. ARRIVED = stop and mine.", contentX + 6, panelY + 32, COL_DIM, false);
        g.text(font, "Save JSON for routes or export a Java snippet.", contentX + 6, panelY + 44, COL_DIM, false);
        if (nameField != null && inContentViewport(nameField.y)) {
            g.text(font, "Route:", nameField.x - 36, nameField.y + 2, COL_DIM, false);
        }
        renderWidgetsScrolled(g, mouseX, mouseY);
        renderWaypointList(g, mouseX, mouseY);
    }

    private void renderMovementRecorder(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var recorder = BooterClient.movementRecorder();
        int statusColor = recorder.isRecording() ? COL_ACTIVE : COL_DIM;
        g.text(font, "Status: " + (recorder.isRecording() ? "Recording ground movement" : "Idle"),
                contentX + 6, panelY + 20, statusColor, false);
        g.text(font, "Nodes: " + recorder.size() + " · END nodes: " + recorder.endNodeCount()
                + " · Air ticks skipped: " + recorder.skippedAirTicks(), contentX + 6, panelY + 32, COL_TEXT, false);
        g.text(font, "Only samples while you are on the ground.", contentX + 6, panelY + 44, COL_DIM, false);
        if (nameField != null && inContentViewport(nameField.y)) {
            g.text(font, "File:", nameField.x - 49, nameField.y + 2, COL_DIM, false);
        }
        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void renderBlockMiner(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var bm = BooterClient.blockMiner();

        String status = switch (bm.getState()) {
            case IDLE -> "Idle";
            case TRAVELING -> "Walking to waypoint";
            case SEEKING -> "Searching for blocks…";
            case WALKING -> "Walking to block";
            case MINING -> "Mining block";
            case TUNNEL -> "Tunneling";
        };
        if (bm.isRouteActive()) {
            status += " (wp " + (bm.getRouteIndex() + 1) + "/" + BooterClient.waypoints().size() + ")";
        }
        String tunnel = bm.getTunnelProgress();
        if (tunnel != null) {
            status += " (" + tunnel + ")";
        }
        int statusColor = bm.getState() == com.booter.client.miner.BlockMinerModule.State.IDLE ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        // Debug line: target, score, current vein size, path length, candidate count.
        var bt = bm.getTarget();
        String t = bt == null ? "(none)" : bt.getX() + " " + bt.getY() + " " + bt.getZ();
        String debug = String.format("Tgt %s · score %.2f · vein %d · path %d · seen %d",
                t, bm.getCurrentScore(), bm.getVeinSize(), bm.getPath().size(), bm.getCandidateCount());
        g.text(font, debug, contentX + 6, panelY + 31, COL_DIM, false);

        // Scrollable route-management labels (clip with the widgets).
        g.enableScissor(contentX, contentViewTop, panelX + panelW, contentViewBottom);
        if (nameField != null && inContentViewport(nameField.y)) {
            g.text(font, "Route:", nameField.x - 36, nameField.y + 2, COL_DIM, false);
        }
        if (inContentViewport(minerRoutesLabelY)) {
            g.text(font, "Saved routes (" + cachedRoutes.size() + "):", contentX + 6, minerRoutesLabelY, COL_TEXT, false);
        }
        g.disableScissor();

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void renderMushroomFarmer(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        var farmer = BooterClient.mushroomFarmer();

        String status = switch (farmer.getState()) {
            case IDLE -> "Idle";
            case SEEKING -> "Searching for mushrooms…";
            case WALKING -> "Walking to mushroom";
            case LOOKING -> "Looking (" + String.format("%.2fs", BooterClient.config().settings.mushroomLookSeconds) + ")";
            case MINING -> "Mining mushroom";
        };
        int statusColor = farmer.getState() == com.booter.client.mushroom.MushroomFarmerModule.State.IDLE
                ? COL_DIM : COL_ACTIVE;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        var target = farmer.getTarget();
        String t = target == null ? "(none)" : target.getX() + ", " + target.getY() + ", " + target.getZ();
        g.text(font, "Target: " + t, contentX + 6, panelY + 32, COL_TEXT, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 30;
        g.text(font, "Finds red/brown mushrooms nearby,", dx, dy, COL_DIM, false);
        g.text(font, "paths to one, looks at it, then mines", dx, dy + 11, COL_DIM, false);
        g.text(font, "it by left-clicking. On your own world.", dx, dy + 22, COL_DIM, false);

        renderWidgetsScrolled(g, mouseX, mouseY);
    }

    private void renderPathfinder(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        PathfinderModule pf = BooterClient.pathfinder();

        String status = pf.getState() == PathfinderModule.State.FOLLOWING
                ? (pf.getMode() == PathfinderModule.Mode.WATER ? "Swimming" : "Following")
                + " — node " + pf.getIndex() + "/" + pf.getPath().size()
                : "Idle";
        int statusColor = pf.getState() == PathfinderModule.State.FOLLOWING ? COL_ACTIVE : COL_DIM;
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);
        var navStatus = BooterClient.navigation().status();
        g.text(font, "Hierarchy: " + navStatus.status + "  replans " + navStatus.replans
                + "  remaining " + String.format(Locale.ROOT, "%.1f", navStatus.distanceRemaining),
                contentX + 6, panelY + 30, COL_DIM, false);

        g.text(font, "Target position:", contentX + 6, panelY + 16 + 28, COL_DIM, false);
        if (inContentViewport(pfFieldY)) {
            g.text(font, "X", pfInnerX, pfFieldY + 2, COL_DIM, false);
            g.text(font, "Y", pfInnerX + pfCellW, pfFieldY + 2, COL_DIM, false);
            g.text(font, "Z", pfInnerX + pfCellW * 2, pfFieldY + 2, COL_DIM, false);
        }

        renderWidgetsScrolled(g, mouseX, mouseY);

        renderPathList(g);
    }

    /** Read-only list of the computed path nodes, auto-scrolled to the active one. */
    private void renderPathList(GuiGraphicsExtractor g) {
        Font font = font();
        PathfinderModule pf = BooterClient.pathfinder();
        List<BlockPos> path = pf.getPath();

        g.text(font, "Path (" + path.size() + ")", listX, listY - 11, COL_TEXT, false);
        g.fill(listX, listY, listX + listW, listY + listH, COL_LIST_BG);
        g.outline(listX, listY, listW, listH, COL_BORDER);

        if (path.isEmpty()) {
            g.text(font, "No path computed.", listX + 5, listY + 5, COL_DIM, false);
            g.text(font, "Set a target and", listX + 5, listY + 16, COL_DIM, false);
            g.text(font, "press Pathfind.", listX + 5, listY + 27, COL_DIM, false);
            return;
        }

        int idx = pf.getIndex();
        int rows = (listH - 4) / LIST_ROW_H;
        int startRow = Mth.clamp(idx - rows / 2, 0, Math.max(0, path.size() - rows));

        g.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
        for (int r = 0; r < rows && startRow + r < path.size(); r++) {
            int i = startRow + r;
            BlockPos p = path.get(i);
            int rowY = listY + 2 + r * LIST_ROW_H;
            boolean active = i == idx;
            if (active) {
                g.fill(listX + 1, rowY, listX + listW - 1, rowY + LIST_ROW_H, COL_ROW_HOVER);
            }
            String label = String.format("#%d  %d %d %d", i + 1, p.getX(), p.getY(), p.getZ());
            g.text(font, label, listX + 4, rowY + 1, active ? COL_ACTIVE : COL_TEXT, false);
        }
        g.disableScissor();
    }

    private void renderMushroom(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        WaypointWalkerModule walker = BooterClient.walker();
        WaypointManager manager = BooterClient.waypoints();

        String status = switch (walker.getState()) {
            case STOPPED -> "Stopped";
            case WALKING -> "Running — waypoint " + (walker.getCurrentIndex() + 1) + "/" + manager.size();
            case PAUSED -> "Paused — waypoint " + (walker.getCurrentIndex() + 1) + "/" + manager.size();
        };
        int statusColor = switch (walker.getState()) {
            case STOPPED -> COL_DIM;
            case WALKING -> COL_ACTIVE;
            case PAUSED -> 0xFFD29922;
        };
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        int dx = contentX + 6;
        int dy = panelY + 16 + 18;
        g.text(font, "Hardcoded mushroom route (bundled in the mod).", dx, dy, COL_TEXT, false);
        g.text(font, "Starting applies this preset:", dx, dy + 13, COL_DIM, false);
        g.text(font, "- Auto Jump ON", dx + 4, dy + 25, COL_ACCENT, false);
        g.text(font, "- Locked pitch 30°", dx + 4, dy + 36, COL_ACCENT, false);
        g.text(font, "- Hold Sprint ON", dx + 4, dy + 47, COL_ACCENT, false);

        renderWidgetsScrolled(g, mouseX, mouseY);

        renderWaypointList(g, mouseX, mouseY);
    }

    private void renderRouteWalker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        WaypointWalkerModule walker = BooterClient.walker();
        WaypointManager manager = BooterClient.waypoints();

        // Status line
        String status = switch (walker.getState()) {
            case STOPPED -> "Stopped";
            case WALKING -> "Walking — waypoint " + (walker.getCurrentIndex() + 1) + "/" + manager.size();
            case PAUSED -> "Paused — waypoint " + (walker.getCurrentIndex() + 1) + "/" + manager.size();
        };
        int statusColor = switch (walker.getState()) {
            case STOPPED -> COL_DIM;
            case WALKING -> COL_ACTIVE;
            case PAUSED -> 0xFFD29922;
        };
        g.text(font, "Status: " + status, contentX + 6, panelY + 20, statusColor, false);

        // Route name label
        if (nameField != null && inContentViewport(nameField.y)) {
            g.text(font, "Route:", nameField.x - 36, nameField.y + 2, COL_DIM, false);
        }

        renderWidgetsScrolled(g, mouseX, mouseY);

        renderWaypointList(g, mouseX, mouseY);
    }

    private void renderWaypointList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = font();
        WaypointManager manager = BooterClient.waypoints();
        List<Waypoint> list = manager.view();

        String heading = "Waypoints (" + list.size() + ")";
        g.text(font, heading, listX, listY - 11, COL_TEXT, false);

        g.fill(listX, listY, listX + listW, listY + listH, COL_LIST_BG);
        g.outline(listX, listY, listW, listH, COL_BORDER);

        if (list.isEmpty()) {
            g.text(font, "No waypoints yet.", listX + 5, listY + 5, COL_DIM, false);
            g.text(font, "Press the Add key", listX + 5, listY + 16, COL_DIM, false);
            g.text(font, "or the Add button.", listX + 5, listY + 27, COL_DIM, false);
            return;
        }

        clampScroll();
        int activeIndex = BooterClient.walker().getState() == WaypointWalkerModule.State.STOPPED
                ? -1 : BooterClient.walker().getCurrentIndex();

        g.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
        int yOffset = listY + 2 - (int) listScroll;
        for (int i = 0; i < list.size(); i++) {
            int rowY = yOffset + i * LIST_ROW_H;
            if (rowY + LIST_ROW_H < listY || rowY > listY + listH) {
                continue;
            }
            boolean hovered = mouseX >= listX + 1 && mouseX < listX + listW - 1
                    && mouseY >= rowY && mouseY < rowY + LIST_ROW_H
                    && mouseY >= listY && mouseY < listY + listH;
            if (hovered) {
                g.fill(listX + 1, rowY, listX + listW - 1, rowY + LIST_ROW_H, COL_ROW_HOVER);
            }
            Waypoint wp = list.get(i);
            boolean active = i == activeIndex;
            String label = String.format("#%d %s %.0f %.0f %.0f",
                    i + 1, waypointTypeShort(wp), wp.x, wp.y, wp.z);
            g.text(font, label, listX + 4, rowY + 1, active ? COL_ACTIVE : COL_TEXT, false);
            // Per-row delete button
            int delX = listX + listW - 12;
            boolean delHover = hovered && mouseX >= delX;
            g.text(font, "x", delX + 2, rowY + 1, delHover ? 0xFFFF6A69 : COL_DIM, false);
        }
        g.disableScissor();

        // Scrollbar
        int contentH = list.size() * LIST_ROW_H + 4;
        if (contentH > listH) {
            int barH = Math.max(8, listH * listH / contentH);
            int barY = listY + (int) ((listH - barH) * (listScroll / (contentH - listH)));
            g.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, COL_ACCENT);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Sidebar section tabs
            for (int i = 0; i < SECTIONS.length; i++) {
                int ty = tabY0 + i * tabGap;
                if (mouseX >= tabX && mouseX < tabX + tabW && mouseY >= ty && mouseY < ty + tabH) {
                    selectSection(i);
                    return true;
                }
            }
        }
        // Only the visible (un-clipped) part of the scrollable settings area is interactive.
        boolean inView = inContentViewport(mouseY);
        focusedField = null;
        if (inView) {
            for (Widget widget : widgets) {
                if (widget instanceof TextField tf) {
                    tf.focused = tf.contains(mouseX, mouseY);
                    if (tf.focused) {
                        focusedField = tf;
                    }
                }
            }
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (inView) {
                for (Widget widget : widgets) {
                    if (widget.mouseClicked(mouseX, mouseY)) {
                        return true;
                    }
                }
            }
            if (handleListClick(mouseX, mouseY)) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean handleListClick(double mouseX, double mouseY) {
        if (selectedSection != 0 && selectedSection != 1 && selectedSection != 13) {
            return false; // only the waypoint sections have an interactive list
        }
        List<Waypoint> list = BooterClient.waypoints().view();
        if (list.isEmpty()
                || mouseX < listX || mouseX >= listX + listW
                || mouseY < listY || mouseY >= listY + listH) {
            return false;
        }
        int index = (int) ((mouseY - (listY + 2 - listScroll)) / LIST_ROW_H);
        if (index < 0 || index >= list.size()) {
            return true; // clicked empty list area
        }
        playClick();
        if (mouseX >= listX + listW - 12) {
            if (BooterClient.waypoints().removeAt(index)) {
                BooterClient.chat("Removed waypoint #" + (index + 1) + ".");
                BooterClient.walker().onWaypointsMutated(mc());
            }
        } else {
            BooterClient.walker().jumpTo(mc(), index);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        for (Widget widget : widgets) {
            if (widget.mouseDragged(mouseX, mouseY)) {
                return true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        for (Widget widget : widgets) {
            widget.mouseReleased();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Interactive waypoint list (Route Walker / Mushroom Macro / Dev Mode) has its own scroll.
        if ((selectedSection == 0 || selectedSection == 1 || selectedSection == 14)
                && mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            listScroll -= verticalAmount * LIST_ROW_H * 2;
            clampScroll();
            return true;
        }
        // Scroll the settings widgets when the cursor is over the content area.
        if (contentMax > 0 && mouseX >= contentX && mouseX < panelX + panelW && inContentViewport(mouseY)) {
            contentScroll -= verticalAmount * 14.0;
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void clampScroll() {
        int contentH = BooterClient.waypoints().size() * LIST_ROW_H + 4;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, contentH - listH));
    }

    private static String sanitizeRouteName(String name) {
        String cleaned = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        return cleaned.isBlank() ? "route" : cleaned;
    }

    private static String waypointTypeLabel(Waypoint.Type type) {
        return type == Waypoint.Type.WALKING ? "walking" : "arrived";
    }

    private static String waypointTypeShort(Waypoint wp) {
        return wp != null && wp.type == Waypoint.Type.WALKING ? "W" : "A";
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (focusedField != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER
                    || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                focusedField.focused = false;
                focusedField = null;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                focusedField.backspace();
                return true;
            }
            return true; // swallow other keys while typing
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (focusedField != null) {
            focusedField.type((char) event.codepoint());
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        BooterClient.config().save();
        super.onClose();
    }

    private void playClick() {
        mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    // ------------------------------------------------------------------ widgets

    private abstract static class Widget {
        int x;
        int y;
        int w;
        int h;

        Widget(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        abstract void render(GuiGraphicsExtractor g, int mouseX, int mouseY);

        boolean mouseClicked(double mx, double my) {
            return false;
        }

        boolean mouseDragged(double mx, double my) {
            return false;
        }

        void mouseReleased() {
        }
    }

    private final class Toggle extends Widget {
        private final String label;
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;

        Toggle(int x, int y, int w, String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
            super(x, y, w, 11);
            this.label = label;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            boolean on = getter.get();
            g.text(font(), label, x, y + 1, contains(mouseX, mouseY) ? COL_TEXT : COL_DIM, false);
            int pillX = x + w - 19;
            int pillY = y + 1;
            g.fill(pillX, pillY, pillX + 18, pillY + 9, on ? COL_ON : COL_OFF);
            g.outline(pillX, pillY, 18, 9, COL_BORDER);
            int knobX = on ? pillX + 10 : pillX + 1;
            g.fill(knobX, pillY + 1, knobX + 7, pillY + 8, 0xFFE6EDF3);
        }

        @Override
        boolean mouseClicked(double mx, double my) {
            if (!contains(mx, my)) {
                return false;
            }
            playClick();
            setter.accept(!getter.get());
            return true;
        }
    }

    private final class Button extends Widget {
        private final Supplier<String> label;
        private final boolean danger;
        private final Runnable action;

        Button(int x, int y, int w, int h, Supplier<String> label, boolean danger, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.danger = danger;
            this.action = action;
        }

        @Override
        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            boolean hovered = contains(mouseX, mouseY);
            int bg = danger ? (hovered ? COL_DANGER : 0xFF7D1A1B) : (hovered ? COL_BUTTON_HOVER : COL_BUTTON);
            g.fill(x, y, x + w, y + h, bg);
            g.outline(x, y, w, h, hovered ? COL_ACCENT : COL_BORDER);
            String text = label.get();
            g.text(font(), text, x + (w - font().width(text)) / 2, y + (h - 8) / 2, COL_TEXT, false);
        }

        @Override
        boolean mouseClicked(double mx, double my) {
            if (!contains(mx, my)) {
                return false;
            }
            playClick();
            action.run();
            return true;
        }
    }

    private final class Slider extends Widget {
        private final String label;
        private final float min;
        private final float max;
        private final Supplier<Float> getter;
        private final Consumer<Float> setter;
        private final java.util.function.Function<Float, String> formatter;
        private boolean dragging;

        Slider(int x, int y, int w, String label, float min, float max,
               Supplier<Float> getter, Consumer<Float> setter,
               java.util.function.Function<Float, String> formatter) {
            super(x, y, w, 17);
            this.label = label;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.formatter = formatter;
        }

        @Override
        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            Font font = font();
            g.text(font, label, x, y, COL_DIM, false);
            String value = formatter.apply(getter.get());
            g.text(font, value, x + w - font.width(value), y, COL_TEXT, false);

            int trackY = y + 11;
            g.fill(x, trackY, x + w, trackY + 3, COL_OFF);
            float fraction = (getter.get() - min) / (max - min);
            int filled = (int) (w * Mth.clamp(fraction, 0.0f, 1.0f));
            g.fill(x, trackY, x + filled, trackY + 3, COL_ACCENT);
            int knobX = Mth.clamp(x + filled - 1, x, x + w - 3);
            g.fill(knobX, trackY - 2, knobX + 3, trackY + 5, 0xFFE6EDF3);
        }

        @Override
        boolean mouseClicked(double mx, double my) {
            if (mx < x || mx >= x + w || my < y + 7 || my >= y + h) {
                return false;
            }
            dragging = true;
            setFromMouse(mx);
            return true;
        }

        @Override
        boolean mouseDragged(double mx, double my) {
            if (!dragging) {
                return false;
            }
            setFromMouse(mx);
            return true;
        }

        @Override
        void mouseReleased() {
            dragging = false;
        }

        private void setFromMouse(double mx) {
            float fraction = Mth.clamp((float) ((mx - x) / w), 0.0f, 1.0f);
            float raw = min + fraction * (max - min);
            // Snap to two decimals: fine enough for the rotation multiplier (0.01..1.0)
            // while staying readable for pitch/radius/etc.
            setter.accept(Math.round(raw * 100.0f) / 100.0f);
        }
    }

    /** Editable single-line text field backed by a getter/setter pair. */
    private final class TextField extends Widget {
        private final Supplier<String> getter;
        private final Consumer<String> setter;
        private final int maxLen;
        private final IntPredicate allow;
        private boolean focused;

        TextField(int x, int y, int w, int h, Supplier<String> getter, Consumer<String> setter,
                  int maxLen, IntPredicate allow) {
            super(x, y, w, h);
            this.getter = getter;
            this.setter = setter;
            this.maxLen = maxLen;
            this.allow = allow;
        }

        @Override
        void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            g.fill(x, y, x + w, y + h, 0xFF0D1117);
            g.outline(x, y, w, h, focused ? COL_ACCENT : COL_BORDER);
            String value = getter.get();
            boolean cursor = focused && (System.currentTimeMillis() / 500) % 2 == 0;
            g.text(font(), value + (cursor ? "_" : ""), x + 3, y + 2, COL_TEXT, false);
        }

        @Override
        boolean mouseClicked(double mx, double my) {
            return contains(mx, my);
        }

        void type(char c) {
            String v = getter.get();
            if (v.length() < maxLen && allow.test(c)) {
                setter.accept(v + c);
            }
        }

        void backspace() {
            String v = getter.get();
            if (!v.isEmpty()) {
                setter.accept(v.substring(0, v.length() - 1));
            }
        }
    }
}
