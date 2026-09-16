package com.booter.client.pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.rotation.RotationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Collections;
import java.util.List;

/**
 * Walks the client player along an A*-computed path to a target block via the
 * shared {@link PathFollower}, using only vanilla movement inputs and the smooth
 * {@link RotationManager}. It physically moves the player like a legitimate
 * client — no packets, no teleportation.
 */
public final class PathfinderModule {
    public enum State {
        IDLE, FOLLOWING
    }

    public enum Mode {
        LAND, WATER
    }

    private static final int MAX_NODES = 12000;
    private static final int MAX_RADIUS = 224;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower follower;
    private final WaterPathFollower waterFollower;

    private State state = State.IDLE;
    private Mode mode = Mode.LAND;
    private BlockPos goal;

    public PathfinderModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
        this.follower = new PathFollower(movement, rotation);
        this.waterFollower = new WaterPathFollower(movement, rotation);
    }

    public State getState() {
        return state;
    }

    public List<BlockPos> getPath() {
        return mode == Mode.WATER ? waterFollower.getPath() : follower.getPath();
    }

    public int getIndex() {
        return mode == Mode.WATER ? waterFollower.getIndex() : follower.getIndex();
    }

    public Mode getMode() {
        return mode;
    }

    /** Computes a path from the player to (x,y,z) and starts following it. */
    public void start(Minecraft client, int x, int y, int z) {
        startLand(client, x, y, z);
    }

    /** Computes a land path from the player to (x,y,z) and starts following it. */
    public void startLand(Minecraft client, int x, int y, int z) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        // Only one mover/actor at a time.
        BooterClient.walker().stopRoute(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        goal = new BlockPos(x, y, z);
        List<BlockPos> found = computePath(client, player.blockPosition(), goal);
        if (found == null || found.size() < 2) {
            state = State.IDLE;
            haltMovement(client);
            BooterClient.chat("Pathfinder: no path to " + x + ", " + y + ", " + z + ".");
            return;
        }
        mode = Mode.LAND;
        waterFollower.clear();
        follower.setPath(found, player);
        state = State.FOLLOWING;
        updateNavigationStatus(found, 0);
        BooterClient.chat("Pathfinder: following " + found.size() + " nodes to " + x + ", " + y + ", " + z + ".");
    }

    /** Computes a 3D water path from the player to (x,y,z) and starts swimming it. */
    public void startWater(Minecraft client, int x, int y, int z) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        BooterClient.walker().stopRoute(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        goal = new BlockPos(x, y, z);
        WaterPathfinder finder = new WaterPathfinder(MAX_NODES, MAX_RADIUS);
        BlockPos swimStart = BlockPos.containing(player.getX(), player.getEyeY() - 0.7, player.getZ());
        List<BlockPos> found = finder.findPath(client.level, swimStart, goal);
        if (found == null || found.size() < 2) {
            state = State.IDLE;
            haltMovement(client);
            BooterClient.chat("Water Pathfinder: no water path to " + x + ", " + y + ", " + z + ".");
            return;
        }
        mode = Mode.WATER;
        follower.clear();
        waterFollower.setPath(found, player);
        state = State.FOLLOWING;
        updateNavigationStatus(found, 0);
        BooterClient.chat("Water Pathfinder: swimming " + found.size() + " nodes with 5-block lookahead.");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        haltMovement(client);
        follower.clear();
        waterFollower.clear();
        state = State.IDLE;
        if (BooterClient.navigation() != null) {
            BooterClient.navigation().status().idle();
        }
        BooterClient.chat("Pathfinder stopped.");
    }

    /**
     * Sets the target to the block the player is looking at, without walking.
     * (Keybind: Set Pathfinder Target.)
     */
    public boolean setTargetToCrosshair(Minecraft client) {
        if (client.hitResult == null || client.hitResult.getType() != HitResult.Type.BLOCK) {
            BooterClient.chat("Pathfinder: look at a block to set the target.");
            return false;
        }
        BlockPos p = ((BlockHitResult) client.hitResult).getBlockPos();
        config.settings.pathTargetX = p.getX();
        config.settings.pathTargetY = p.getY();
        config.settings.pathTargetZ = p.getZ();
        BooterClient.chat("Pathfinder target set to " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ".");
        return true;
    }

    /** Starts pathfinding to the currently stored target. */
    public void startToTarget(Minecraft client) {
        ConfigManager.Settings s = config.settings;
        startLand(client, s.pathTargetX, s.pathTargetY, s.pathTargetZ);
    }

    /** Starts water pathfinding to the currently stored target. */
    public void startWaterToTarget(Minecraft client) {
        ConfigManager.Settings s = config.settings;
        startWater(client, s.pathTargetX, s.pathTargetY, s.pathTargetZ);
    }

    /** Keybind action: start toward the stored target, or stop if already running. */
    public void toggleToTarget(Minecraft client) {
        if (state == State.FOLLOWING) {
            stop(client);
        } else {
            startToTarget(client);
        }
    }

    private List<BlockPos> computePath(Minecraft client, BlockPos start, BlockPos target) {
        Pathfinder finder = new Pathfinder(MAX_NODES, MAX_RADIUS, config.settings.pathfindWater);
        return finder.findPath(client.level, start, target);
    }

    /** Runs every client tick. */
    public void tick(Minecraft client) {
        if (state != State.FOLLOWING) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            haltMovement(client);
            follower.clear();
            waterFollower.clear();
            state = State.IDLE;
            return;
        }

        if (mode == Mode.WATER) {
            WaterPathFollower.Status status = waterFollower.tick(client);
            updateNavigationStatus(waterFollower.getPath(), waterFollower.getIndex());
            switch (status) {
                case ARRIVED -> finish(client);
                case STUCK -> {
                    BlockPos swimStart = BlockPos.containing(player.getX(), player.getEyeY() - 0.7, player.getZ());
                    BooterClient.chat("Water Pathfinder: stuck, routing around edge…");
                    WaterPathfinder finder = new WaterPathfinder(MAX_NODES, MAX_RADIUS)
                            .avoidNear(swimStart, 3.0, 120.0);
                    List<BlockPos> repath = finder.findPath(client.level, swimStart, goal);
                    if (repath == null || repath.size() < 2) {
                        BooterClient.chat("Water Pathfinder: no water path from current position.");
                        stop(client);
                    } else {
                        waterFollower.setPath(repath, player);
                    }
                }
                case IDLE -> {
                    haltMovement(client);
                    waterFollower.clear();
                    state = State.IDLE;
                }
                default -> {
                }
            }
            return;
        }

        PathFollower.Status status = follower.tick(client, false, false);
        updateNavigationStatus(follower.getPath(), follower.getIndex());
        switch (status) {
            case ARRIVED -> finish(client);
            case STUCK -> {
                BooterClient.chat("Pathfinder: stuck, recomputing…");
                List<BlockPos> repath = computePath(client, player.blockPosition(), goal);
                if (repath == null || repath.size() < 2) {
                    BooterClient.chat("Pathfinder: no path from current position.");
                    stop(client);
                    } else {
                        follower.setPath(repath, player);
                        if (BooterClient.navigation() != null) {
                            BooterClient.navigation().status().replans++;
                        }
                    }
                }
            case IDLE -> {
                haltMovement(client);
                follower.clear();
                state = State.IDLE;
            }
            default -> {
            }
        }
    }

    private void finish(Minecraft client) {
        haltMovement(client);
        follower.clear();
        waterFollower.clear();
        state = State.IDLE;
        if (BooterClient.navigation() != null) {
            BooterClient.navigation().status().idle();
        }
        BooterClient.chat(mode == Mode.WATER ? "Water Pathfinder: destination reached." : "Pathfinder: destination reached.");
    }

    private void updateNavigationStatus(List<BlockPos> path, int index) {
        if (BooterClient.navigation() == null || path == null || path.isEmpty()) {
            return;
        }
        var status = BooterClient.navigation().status();
        status.status = mode == Mode.WATER ? "swimming" : "navigating";
        status.currentSegment = index;
        status.progress = path.size() <= 1 ? 1.0 : Math.min(1.0, Math.max(0.0, index / (double) (path.size() - 1)));
        status.distanceRemaining = remainingDistance(path, index);
    }

    private static double remainingDistance(List<BlockPos> path, int index) {
        double total = 0.0;
        for (int i = Math.max(0, index); i + 1 < path.size(); i++) {
            total += Math.sqrt(path.get(i).distSqr(path.get(i + 1)));
        }
        return total;
    }

    private void haltMovement(Minecraft client) {
        movement.releaseAll(client);
        rotation.setActive(false);
    }
}
