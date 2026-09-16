package com.booter.client;

import com.booter.client.config.ConfigManager;
import com.booter.client.azalea.FloweringAzaleaFarmerModule;
import com.booter.client.combat.CombatModule;
import com.booter.client.input.KeybindManager;
import com.booter.client.coal.CoalMinerModule;
import com.booter.client.farmer.AutoFarmerModule;
import com.booter.client.fishhunter.FishHunterModule;
import com.booter.client.fisher.AutoFisherModule;
import com.booter.client.miner.BlockMinerModule;
import com.booter.client.movement.MovementController;
import com.booter.client.mushroom.MushroomFarmerModule;
import com.booter.client.navigation.NavigationWaypointManager;
import com.booter.client.pathdebug.PathDebugOverlay;
import com.booter.client.pathdebug.PathDebugRenderer;
import com.booter.client.pathfinder.PathfinderModule;
import com.booter.client.render.RouteRenderer;
import com.booter.client.rotation.RotationManager;
import com.booter.client.turtlehunter.TurtleHunterModule;
import com.booter.client.waypoint.WaypointManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Booter Client entrypoint. Wires the Waypoint Walker module together:
 * config, waypoint storage, movement/rotation controllers, world rendering
 * and keybinds. Entirely client-side.
 */
public final class BooterClient implements ClientModInitializer {
    public static final String MOD_ID = "booterclient";
    public static final Logger LOGGER = LoggerFactory.getLogger("BooterClient");

    private static BooterClient instance;

    private ConfigManager config;
    private WaypointManager waypoints;
    private NavigationWaypointManager navigation;
    private MovementController movement;
    private RotationManager rotation;
    private WaypointWalkerModule walker;
    private PathfinderModule pathfinder;
    private MushroomFarmerModule mushroomFarmer;
    private BlockMinerModule blockMiner;
    private AutoFisherModule autoFisher;
    private FishHunterModule fishHunter;
    private TurtleHunterModule turtleHunter;
    private CombatModule combat;
    private FloweringAzaleaFarmerModule azaleaFarmer;
    private CoalMinerModule coalMiner;
    private AutoFarmerModule autoFarmer;
    private RouteRenderer renderer;
    private PathDebugRenderer pathDebugRenderer;
    private KeybindManager keybinds;

    @Override
    public void onInitializeClient() {
        instance = this;

        config = new ConfigManager();
        config.load();

        waypoints = new WaypointManager(config);
        waypoints.loadCurrent();
        navigation = new NavigationWaypointManager(config);
        navigation.loadWaypoints();

        movement = new MovementController();
        rotation = new RotationManager();
        walker = new WaypointWalkerModule(config, waypoints, movement, rotation);
        pathfinder = new PathfinderModule(config, movement, rotation);
        mushroomFarmer = new MushroomFarmerModule(config, movement, rotation);
        blockMiner = new BlockMinerModule(config, movement, rotation);
        autoFisher = new AutoFisherModule(config, movement, rotation);
        fishHunter = new FishHunterModule(config, movement, rotation);
        turtleHunter = new TurtleHunterModule(config, movement, rotation);
        combat = new CombatModule(config, movement, rotation);
        azaleaFarmer = new FloweringAzaleaFarmerModule(config, movement, rotation);
        coalMiner = new CoalMinerModule(config, movement, rotation);
        autoFarmer = new AutoFarmerModule(config, movement, rotation);
        renderer = new RouteRenderer(config, waypoints, walker);
        pathDebugRenderer = new PathDebugRenderer();
        keybinds = new KeybindManager();

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            keybinds.tick(client);
            walker.tick(client);
            pathfinder.tick(client);
            mushroomFarmer.tick(client);
            blockMiner.tick(client);
            autoFisher.tick(client);
            fishHunter.tick(client);
            turtleHunter.tick(client);
            combat.tick(client);
            azaleaFarmer.tick(client);
            coalMiner.tick(client);
            autoFarmer.tick(client);
        });
        // Rotations advance every rendered frame for maximum smoothness (modules set
        // their target in the tick above; this eases the view toward it). The step is
        // delta-time scaled, so the turn rate is identical at any FPS and stays stable
        // through FPS drops. START_MAIN fires once at the start of each level render.
        LevelRenderEvents.START_MAIN.register(context -> {
            rotation.setMultiplier(config.settings.rotationMultiplier);
            rotation.setRandomize(config.settings.microRotations);
            rotation.update();
        });
        LevelRenderEvents.END_MAIN.register(renderer::render);
        // A* search visualizer — in-world nodes/path plus the on-screen stats panel.
        LevelRenderEvents.END_MAIN.register(pathDebugRenderer::render);
        HudElementRegistry.addLast(PathDebugOverlay.ID, new PathDebugOverlay());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> config.save());

        LOGGER.info("Booter Client initialized (Waypoint Walker ready)");
    }

    public static ConfigManager config() {
        return instance.config;
    }

    public static WaypointManager waypoints() {
        return instance.waypoints;
    }

    public static NavigationWaypointManager navigation() {
        return instance.navigation;
    }

    public static WaypointWalkerModule walker() {
        return instance.walker;
    }

    public static PathfinderModule pathfinder() {
        return instance.pathfinder;
    }

    public static MushroomFarmerModule mushroomFarmer() {
        return instance.mushroomFarmer;
    }

    public static BlockMinerModule blockMiner() {
        return instance.blockMiner;
    }

    public static AutoFisherModule autoFisher() {
        return instance.autoFisher;
    }

    public static FishHunterModule fishHunter() {
        return instance.fishHunter;
    }

    public static TurtleHunterModule turtleHunter() {
        return instance.turtleHunter;
    }

    public static CombatModule combat() {
        return instance.combat;
    }

    public static FloweringAzaleaFarmerModule azaleaFarmer() {
        return instance.azaleaFarmer;
    }

    public static CoalMinerModule coalMiner() {
        return instance.coalMiner;
    }

    public static AutoFarmerModule autoFarmer() {
        return instance.autoFarmer;
    }

    /** Sends a prefixed chat message to the local player only. */
    public static void chat(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        client.player.sendSystemMessage(
                Component.literal("[Booter] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
    }

    /** Shows a message on the action bar (above the hotbar). */
    public static void overlay(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        client.player.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    public static void runCommand(Minecraft client, String command) {
        if (client.player == null || client.player.connection == null) {
            return;
        }
        client.player.connection.sendCommand(command);
        overlay("/" + command);
    }
}
