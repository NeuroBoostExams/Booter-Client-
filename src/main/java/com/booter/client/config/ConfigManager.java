package com.booter.client.config;

import com.booter.client.BooterClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves all Booter Client settings as JSON in
 * {@code config/booterclient/config.json}.
 */
public final class ConfigManager {
    public static final float MIN_RADIUS = 0.5f;
    public static final float MAX_RADIUS = 5.0f;
    public static final float DEFAULT_RADIUS = 2.0f;

    public static final float MIN_ROTATION_MULTIPLIER = 0.01f;
    public static final float MAX_ROTATION_MULTIPLIER = 1.0f;
    public static final float DEFAULT_ROTATION_MULTIPLIER = 0.15f;

    public static final float MIN_PITCH = -90.0f;
    public static final float MAX_PITCH = 90.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Plain settings holder serialized 1:1 to JSON. */
    public static final class Settings {
        public boolean loopRoute = false;
        public boolean holdSprint = false;
        public boolean holdCrouch = false;
        public boolean holdLeftClick = false;
        public boolean autoJump = false;
        public boolean renderWaypoints = true;
        public boolean microRotations = false;
        public float customPitch = 0.0f;
        public float rotationMultiplier = DEFAULT_ROTATION_MULTIPLIER;
        public float waypointRadius = DEFAULT_RADIUS;
        public float removeDistance = 8.0f;
        public String lastRouteName = "route";

        // Pathfinder
        public boolean pathfindWater = false;
        public int pathTargetX = 0;
        public int pathTargetY = 64;
        public int pathTargetZ = 0;
        public boolean hierarchicalPathfinding = true;
        public boolean hierarchyDebug = false;
        public int hierarchyMinDistance = 64;
        public int hierarchyWaypointSearchRadius = 256;
        public int hierarchyMaxLocalDistance = 96;
        public int hierarchyEdgeInvalidSeconds = 30;
        public int hierarchyCacheLifetimeSeconds = 120;

        // Mushroom Farmer
        public float mushroomLookSeconds = 3.25f;
        public int mushroomScanRadius = 20;

        // Auto Fisher — advanced (after N catches: look down, right-click once, recast)
        public boolean fisherAdvanced = false;
        public int fisherCatchTarget = 5;

        // Fish Hunter — pathfinds to fish mobs and attacks with selected hotbar slot
        public float fishHunterDistance = 2.0f;
        public int fishHunterSlot = 1; // hotbar slot (1-8)
        public boolean fishHunterRightClick = true;

        // Turtle Hunter — fish-hunter movement/interaction, but turtle-only
        public float turtleHunterDistance = 2.0f;
        public int turtleHunterSlot = 1; // hotbar slot (1-8)
        public boolean turtleHunterRightClick = true;

        // Combat — mob type filters
        public boolean combatOneTap = false;
        public boolean combatZombie = false;
        public boolean combatSkeleton = false;
        public boolean combatCreeper = false;
        public boolean combatChargedCreeper = false;
        public boolean combatSpider = false;
        public boolean combatEnderman = false;
        public boolean combatWitch = false;
        public boolean combatSlime = false;
        public boolean combatNetherMobs = false;
        public boolean combatIllagers = false;
        public boolean combatGuardians = false;
        public boolean combatArthropods = false;
        public boolean combatWardenBreeze = false;
        public boolean combatAnimals = false;
        public boolean combatAquatic = false;
        public boolean combatVillagersGolems = false;

        // Flowering Azalea Farmer
        public int azaleaScanRadius = 24;
        public int azaleaScanInterval = 10;
        public float azaleaMineRange = 4.5f;

        // Coal Miner — digs through stone to buried coal veins
        public int coalScanRadius = 32;
        public int coalMineSlot = 2;   // hotbar slot (1-9) to hold while mining

        // Auto Farmer — walks a saved route, harvesting crops in front
        public String farmerRoute = "plot4";
        public boolean farmerFarm = true;     // harvest while walking, or only walk
        public boolean farmerAllCrops = true; // include nether wart / cocoa / berries too
        public boolean farmerCrouch = true;   // hold sneak while farming (edge safety)

        // Block Miner — behaviour
        public int minerScanRadius = 24;
        public boolean minerUseRoute = false;    // walk the waypoint route, mining at each stop
        // Block Miner — tunnel (strip-mine) pattern
        public boolean minerTunnelMode = false;  // dig parallel straight tunnels instead of vein/route
        public int minerTunnelLength = 16;       // blocks dug per tunnel
        public int minerTunnelCount = 4;         // number of parallel tunnels
        public int minerTunnelHeight = 2;        // tunnel cross-section height
        public boolean minerTunnelMineOres = true; // also mine ores the tunnel exposes
        public boolean minerCrouch = true;
        public float minerRange = 4.5f;          // how close counts as "in reach" to mine
        public int minerScanInterval = 10;       // ticks between scans / re-evaluations
        public int minerSwitchDelay = 20;        // min ticks between target switches
        public int minerMineTimeout = 100;       // ticks to spend on one block before skipping
        public float minerPriorityMultiplier = 1.0f;
        public float minerSwitchThreshold = 0.25f; // fractional score improvement needed to switch

        // Block Miner — which block types to mine
        public boolean mineDiamondBlock = false;
        public boolean mineIronBlock = false;
        public boolean mineEmeraldBlock = false;
        public boolean mineBlueWool = false;
        public boolean mineGrayWool = false;
        public boolean mineGrayTerracotta = false;
        public boolean mineQuartzBlock = false;
        public boolean minePrismarine = false;
        public boolean mineDarkPrismarine = false;
        public boolean minePrismarineBricks = false;
        public boolean mineLogs = false;
        public boolean mineCoalBlock = false;
        public boolean mineGlass = false; // all glass blocks AND panes (every colour)
    }

    public Settings settings = new Settings();

    private final Path configDir = FabricLoader.getInstance().getConfigDir().resolve(BooterClient.MOD_ID);
    private final Path configFile = configDir.resolve("config.json");

    public Path routesDir() {
        Path dir = configDir.resolve("routes");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to create routes directory", e);
        }
        return dir;
    }

    public Path currentRouteFile() {
        return configDir.resolve("waypoints.json");
    }

    public void load() {
        try {
            Files.createDirectories(configDir);
            if (Files.exists(configFile)) {
                try (var reader = Files.newBufferedReader(configFile)) {
                    Settings loaded = GSON.fromJson(reader, Settings.class);
                    if (loaded != null) {
                        this.settings = loaded;
                    }
                }
            }
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to load config, using defaults", e);
            this.settings = new Settings();
        }
        clamp();
    }

    public void save() {
        clamp();
        try {
            Files.createDirectories(configDir);
            try (var writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to save config", e);
        }
    }

    /** Keeps every setting inside its documented range. */
    public void clamp() {
        settings.waypointRadius = Mth.clamp(settings.waypointRadius, MIN_RADIUS, MAX_RADIUS);
        settings.rotationMultiplier = Mth.clamp(settings.rotationMultiplier, MIN_ROTATION_MULTIPLIER, MAX_ROTATION_MULTIPLIER);
        settings.customPitch = Mth.clamp(settings.customPitch, MIN_PITCH, MAX_PITCH);
        settings.removeDistance = Mth.clamp(settings.removeDistance, 1.0f, 64.0f);
        settings.mushroomLookSeconds = Mth.clamp(settings.mushroomLookSeconds, 0.5f, 10.0f);
        settings.mushroomScanRadius = Mth.clamp(settings.mushroomScanRadius, 4, 48);
        settings.fisherCatchTarget = Mth.clamp(settings.fisherCatchTarget, 1, 20);
        settings.hierarchyMinDistance = Mth.clamp(settings.hierarchyMinDistance, 16, 512);
        settings.hierarchyWaypointSearchRadius = Mth.clamp(settings.hierarchyWaypointSearchRadius, 32, 2048);
        settings.hierarchyMaxLocalDistance = Mth.clamp(settings.hierarchyMaxLocalDistance, 16, 256);
        settings.hierarchyEdgeInvalidSeconds = Mth.clamp(settings.hierarchyEdgeInvalidSeconds, 5, 300);
        settings.hierarchyCacheLifetimeSeconds = Mth.clamp(settings.hierarchyCacheLifetimeSeconds, 10, 1800);
        settings.fishHunterDistance = Mth.clamp(settings.fishHunterDistance, 2.0f, 8.0f);
        settings.fishHunterSlot = Mth.clamp(settings.fishHunterSlot, 1, 8);
        settings.turtleHunterDistance = Mth.clamp(settings.turtleHunterDistance, 2.0f, 8.0f);
        settings.turtleHunterSlot = Mth.clamp(settings.turtleHunterSlot, 1, 8);
        settings.azaleaScanRadius = Mth.clamp(settings.azaleaScanRadius, 4, 64);
        settings.azaleaScanInterval = Mth.clamp(settings.azaleaScanInterval, 1, 40);
        settings.azaleaMineRange = Mth.clamp(settings.azaleaMineRange, 2.0f, 6.0f);
        settings.coalScanRadius = Mth.clamp(settings.coalScanRadius, 8, 96);
        settings.coalMineSlot = Mth.clamp(settings.coalMineSlot, 1, 9);
        settings.minerScanRadius = Mth.clamp(settings.minerScanRadius, 4, 64);
        settings.minerTunnelLength = Mth.clamp(settings.minerTunnelLength, 1, 64);
        settings.minerTunnelCount = Mth.clamp(settings.minerTunnelCount, 1, 16);
        settings.minerTunnelHeight = Mth.clamp(settings.minerTunnelHeight, 2, 4);
        settings.minerRange = Mth.clamp(settings.minerRange, 2.0f, 6.0f);
        settings.minerScanInterval = Mth.clamp(settings.minerScanInterval, 1, 40);
        settings.minerSwitchDelay = Mth.clamp(settings.minerSwitchDelay, 0, 100);
        settings.minerMineTimeout = Mth.clamp(settings.minerMineTimeout, 20, 400);
        settings.minerPriorityMultiplier = Mth.clamp(settings.minerPriorityMultiplier, 0.1f, 10.0f);
        settings.minerSwitchThreshold = Mth.clamp(settings.minerSwitchThreshold, 0.0f, 2.0f);
        if (settings.lastRouteName == null || settings.lastRouteName.isBlank()) {
            settings.lastRouteName = "route";
        }
    }
}
