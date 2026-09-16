package com.booter.client.input;

import com.booter.client.BooterClient;
import com.booter.client.gui.WaypointScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers and handles every Booter Client keybind. All bindings appear in
 * vanilla Options &gt; Controls under the "Booter Client" category.
 */
public final class KeybindManager {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BooterClient.MOD_ID, "main"));

    private final KeyMapping addWaypoint;
    private final KeyMapping removeNearestWaypoint;
    private final KeyMapping clearWaypoints;
    private final KeyMapping toggleWalker;
    private final KeyMapping setPathTarget;
    private final KeyMapping pathfindToTarget;
    private final KeyMapping waterPathfindToTarget;
    private final KeyMapping toggleMushroomFarmer;
    private final KeyMapping toggleBlockMiner;
    private final KeyMapping toggleAutoFisher;
    private final KeyMapping toggleFishHunter;
    private final KeyMapping toggleTurtleHunter;
    private final KeyMapping toggleCombat;
    private final KeyMapping toggleAzaleaFarmer;
    private final KeyMapping toggleCoalMiner;
    private final KeyMapping toggleAutoFarmer;
    private final KeyMapping addRecordedNode;
    private final KeyMapping addRecordedEndNode;
    private final KeyMapping toggleZealotEmanFarmer;
    private final KeyMapping swapToHub;
    private final KeyMapping swapToGalatea;
    private final KeyMapping togglePathDebug;
    private final KeyMapping openGui;

    public KeybindManager() {
        addWaypoint = register("add_waypoint", GLFW.GLFW_KEY_N);
        removeNearestWaypoint = register("remove_nearest_waypoint", GLFW.GLFW_KEY_M);
        clearWaypoints = register("clear_waypoints", GLFW.GLFW_KEY_K);
        toggleWalker = register("toggle_walker", GLFW.GLFW_KEY_J);
        setPathTarget = register("set_target", GLFW.GLFW_KEY_P);
        pathfindToTarget = register("pathfind_target", GLFW.GLFW_KEY_O);
        waterPathfindToTarget = register("water_pathfind_target", GLFW.GLFW_KEY_I);
        toggleMushroomFarmer = register("toggle_mushroom_farmer", GLFW.GLFW_KEY_H);
        toggleBlockMiner = register("toggle_block_miner", GLFW.GLFW_KEY_G);
        toggleAutoFisher = register("toggle_auto_fisher", GLFW.GLFW_KEY_V);
        toggleFishHunter = register("toggle_fish_hunter", GLFW.GLFW_KEY_UNKNOWN);
        toggleTurtleHunter = register("toggle_turtle_hunter", GLFW.GLFW_KEY_UNKNOWN);
        toggleCombat = register("toggle_combat", GLFW.GLFW_KEY_UNKNOWN);
        toggleAzaleaFarmer = register("toggle_azalea_farmer", GLFW.GLFW_KEY_UNKNOWN);
        toggleCoalMiner = register("toggle_coal_miner", GLFW.GLFW_KEY_C);
        toggleAutoFarmer = register("toggle_auto_farmer", GLFW.GLFW_KEY_Y);
        addRecordedNode = register("add_recorded_node", GLFW.GLFW_KEY_UNKNOWN);
        addRecordedEndNode = register("add_recorded_end_node", GLFW.GLFW_KEY_UNKNOWN);
        toggleZealotEmanFarmer = register("toggle_zealot_eman_farmer", GLFW.GLFW_KEY_UNKNOWN);
        swapToHub = register("swap_to_hub", GLFW.GLFW_KEY_LEFT_BRACKET);
        swapToGalatea = register("swap_to_galatea", GLFW.GLFW_KEY_RIGHT_BRACKET);
        togglePathDebug = register("toggle_path_debug", GLFW.GLFW_KEY_B);
        openGui = register("open_gui", GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static KeyMapping register(String name, int key) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.booterclient." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
    }

    /** Runs every client tick. */
    public void tick(Minecraft client) {
        while (addWaypoint.consumeClick()) {
            handleAddWaypoint(client);
        }
        while (removeNearestWaypoint.consumeClick()) {
            handleRemoveNearest(client);
        }
        while (clearWaypoints.consumeClick()) {
            handleClearAll(client);
        }
        while (toggleWalker.consumeClick()) {
            BooterClient.walker().toggleWalking(client);
        }
        while (setPathTarget.consumeClick()) {
            BooterClient.pathfinder().setTargetToCrosshair(client);
        }
        while (pathfindToTarget.consumeClick()) {
            BooterClient.pathfinder().toggleToTarget(client);
        }
        while (waterPathfindToTarget.consumeClick()) {
            BooterClient.pathfinder().startWaterToTarget(client);
        }
        while (toggleMushroomFarmer.consumeClick()) {
            BooterClient.mushroomFarmer().toggle(client);
        }
        while (toggleBlockMiner.consumeClick()) {
            BooterClient.blockMiner().toggle(client);
        }
        while (toggleAutoFisher.consumeClick()) {
            BooterClient.autoFisher().toggle(client);
        }
        while (toggleFishHunter.consumeClick()) {
            BooterClient.fishHunter().toggle(client);
        }
        while (toggleTurtleHunter.consumeClick()) {
            BooterClient.turtleHunter().toggle(client);
        }
        while (toggleCombat.consumeClick()) {
            BooterClient.combat().toggle(client);
        }
        while (toggleAzaleaFarmer.consumeClick()) {
            BooterClient.azaleaFarmer().toggle(client);
        }
        while (toggleCoalMiner.consumeClick()) {
            BooterClient.coalMiner().toggle(client);
        }
        while (toggleAutoFarmer.consumeClick()) {
            BooterClient.autoFarmer().toggle(client);
        }
        while (addRecordedNode.consumeClick()) {
            BooterClient.movementRecorder().addWalkNode(client);
        }
        while (addRecordedEndNode.consumeClick()) {
            BooterClient.movementRecorder().addEndNode(client);
        }
        while (toggleZealotEmanFarmer.consumeClick()) {
            BooterClient.zealotEmanFarmer().toggle(client);
        }
        while (swapToHub.consumeClick()) {
            BooterClient.runCommand(client, "hub");
        }
        while (swapToGalatea.consumeClick()) {
            BooterClient.runCommand(client, "warp galatea");
        }
        while (togglePathDebug.consumeClick()) {
            com.booter.client.pathdebug.PathDebugSettings ds = com.booter.client.pathdebug.PathDebugManager.get().settings;
            ds.enabled = !ds.enabled;
            BooterClient.chat("A* search visualizer " + (ds.enabled ? "enabled." : "disabled."));
        }
        while (openGui.consumeClick()) {
            client.setScreen(new WaypointScreen());
        }
    }

    private void handleAddWaypoint(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        int number = BooterClient.waypoints().add(player.getX(), player.getY(), player.getZ());
        BooterClient.chat(String.format("Added waypoint #%d at (%.1f, %.1f, %.1f).",
                number, player.getX(), player.getY(), player.getZ()));
    }

    private void handleRemoveNearest(Minecraft client) {
        LocalPlayer player = client.player;
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
            BooterClient.walker().onWaypointsMutated(client);
        }
    }

    private void handleClearAll(Minecraft client) {
        int cleared = BooterClient.waypoints().clear();
        BooterClient.chat("Cleared " + cleared + " waypoint(s).");
        BooterClient.walker().onWaypointsMutated(client);
    }
}
