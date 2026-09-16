package com.booter.client;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.rotation.RotationManager;
import com.booter.client.waypoint.Waypoint;
import com.booter.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * The Waypoint Walker module: walks the player along the recorded waypoint list.
 * Between waypoints it pathfinds (A*) and follows the computed node path strictly
 * via the shared {@link PathFollower}, so it hugs walkable routes instead of
 * beelining through walls. It advances when it reaches a waypoint (or its radius)
 * and optionally loops. If a route can't be found it falls back to a direct walk.
 */
public final class WaypointWalkerModule {
    public enum State {
        STOPPED, WALKING, PAUSED
    }

    private static final int MAX_NODES = 12000;
    private static final int MAX_RADIUS = 224;
    /** Ticks to wait before retrying A* after a failed/finished path computation. */
    private static final int REPATH_COOLDOWN = 15;

    private final ConfigManager config;
    private final WaypointManager waypoints;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower follower;

    private boolean enabled = true;
    private State state = State.STOPPED;
    private int currentIndex;
    private int pathCooldown;
    /** True only when A* genuinely found no path (so we may straight-line as a last resort). */
    private boolean pathFailed;

    public WaypointWalkerModule(ConfigManager config, WaypointManager waypoints,
                                MovementController movement, RotationManager rotation) {
        this.config = config;
        this.waypoints = waypoints;
        this.movement = movement;
        this.rotation = rotation;
        this.follower = new PathFollower(movement, rotation);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public State getState() {
        return state;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    /** The A* path currently being followed between waypoints (for rendering). */
    public List<BlockPos> getPath() {
        return follower.getPath();
    }

    public int getPathIndex() {
        return follower.getIndex();
    }

    public void setEnabled(Minecraft client, boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            haltMovement(client);
            state = State.STOPPED;
            BooterClient.chat("Waypoint Walker disabled.");
        } else {
            BooterClient.chat("Waypoint Walker enabled.");
        }
    }

    public void startRoute(Minecraft client) {
        if (!enabled) {
            BooterClient.chat("Waypoint Walker is disabled — enable the module first.");
            return;
        }
        if (waypoints.isEmpty()) {
            BooterClient.chat("No waypoints set. Add waypoints before starting the route.");
            return;
        }
        // Only one mover/actor at a time.
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);
        currentIndex = 0;
        pathCooldown = 0;
        follower.clear();
        state = State.WALKING;
        BooterClient.chat("Route started: " + waypoints.size() + " waypoint(s).");
    }

    public void stopRoute(Minecraft client) {
        if (state == State.STOPPED) {
            return;
        }
        haltMovement(client);
        state = State.STOPPED;
        currentIndex = 0;
        BooterClient.chat("Route stopped.");
    }

    public void pauseRoute(Minecraft client) {
        if (state != State.WALKING) {
            return;
        }
        haltMovement(client);
        state = State.PAUSED;
        BooterClient.chat("Route paused at waypoint #" + (currentIndex + 1) + ".");
    }

    public void resumeRoute(Minecraft client) {
        if (state != State.PAUSED) {
            return;
        }
        if (!enabled) {
            BooterClient.chat("Waypoint Walker is disabled — enable the module first.");
            return;
        }
        pathCooldown = 0;
        follower.clear();
        state = State.WALKING;
        BooterClient.chat("Route resumed toward waypoint #" + (currentIndex + 1) + ".");
    }

    /** Bound to the Toggle Waypoint Walker key. */
    public void toggleWalking(Minecraft client) {
        if (state == State.STOPPED) {
            startRoute(client);
        } else {
            stopRoute(client);
        }
    }

    /** Makes the given waypoint the active target (used by the GUI list). */
    public void jumpTo(Minecraft client, int index) {
        if (index < 0 || index >= waypoints.size()) {
            return;
        }
        currentIndex = index;
        pathCooldown = 0;
        follower.clear();
        if (state == State.STOPPED) {
            if (!enabled) {
                BooterClient.chat("Waypoint Walker is disabled — enable the module first.");
                return;
            }
            state = State.WALKING;
        }
        BooterClient.chat("Heading to waypoint #" + (index + 1) + ".");
    }

    /** Call after the waypoint list was changed externally (remove/clear/load). */
    public void onWaypointsMutated(Minecraft client) {
        follower.clear();
        pathCooldown = 0;
        if (waypoints.isEmpty()) {
            if (state != State.STOPPED) {
                haltMovement(client);
                state = State.STOPPED;
                BooterClient.chat("Route stopped: all waypoints removed.");
            }
            currentIndex = 0;
        } else if (currentIndex >= waypoints.size()) {
            currentIndex = config.settings.loopRoute ? 0 : waypoints.size() - 1;
        }
    }

    /** Runs every client tick. */
    public void tick(Minecraft client) {
        if (state != State.WALKING) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            haltMovement(client);
            state = State.STOPPED;
            currentIndex = 0;
            return;
        }
        if (waypoints.isEmpty()) {
            onWaypointsMutated(client);
            return;
        }
        if (currentIndex >= waypoints.size()) {
            currentIndex = 0;
        }
        if (pathCooldown > 0) {
            pathCooldown--;
        }

        Waypoint target = waypoints.get(currentIndex);
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // Reached the waypoint by its radius — advance and repath THIS tick (so we
        // never straight-line toward the new waypoint before A* routes it, which
        // after a jump would send us back the way we came).
        double radius = config.settings.waypointRadius;
        if (target.squaredDistanceTo(px, py, pz) <= radius * radius) {
            BooterClient.overlay("Waypoint " + (currentIndex + 1) + "/" + waypoints.size() + " reached");
            if (!advanceWaypoint(client)) {
                return; // route complete (and stopped)
            }
            follower.clear();
            pathFailed = false;
            pathCooldown = 0;
            target = waypoints.get(currentIndex);
        }

        // Make sure we have an A* path to the current waypoint.
        if (!follower.isFollowing() && pathCooldown <= 0) {
            pathCooldown = REPATH_COOLDOWN;
            BlockPos start = player.blockPosition();
            BlockPos goal = BlockPos.containing(target.x, target.y, target.z);
            Pathfinder finder = new Pathfinder(MAX_NODES, MAX_RADIUS, config.settings.pathfindWater);
            List<BlockPos> p = finder.findPath(client.level, start, goal);
            if (p != null && p.size() >= 2) {
                follower.setPath(p, player);
                pathFailed = false;
            } else {
                pathFailed = true;
            }
        }

        if (follower.isFollowing()) {
            PathFollower.Status status = follower.tick(client, config.settings.holdCrouch, config.settings.holdLeftClick);
            switch (status) {
                case ARRIVED -> {
                    if (advanceWaypoint(client)) {
                        follower.clear();
                        pathFailed = false;
                        pathCooldown = 0;
                    }
                }
                case STUCK -> {
                    // Recompute toward the same waypoint next tick.
                    follower.clear();
                    pathFailed = false;
                    pathCooldown = 0;
                }
                case IDLE -> {
                    haltMovement(client);
                    state = State.STOPPED;
                    currentIndex = 0;
                }
                default -> {
                }
            }
        } else if (pathFailed) {
            // Genuinely no A* path — fall back to a direct straight-line walk.
            directWalk(client, player, target);
        }
        // Otherwise we're mid-recompute: stand still rather than walk the wrong way.
    }

    /**
     * Advances to the next waypoint, looping or stopping at the end per config.
     * @return true if still walking, false if the route completed and stopped.
     */
    private boolean advanceWaypoint(Minecraft client) {
        if (currentIndex + 1 >= waypoints.size()) {
            if (config.settings.loopRoute) {
                currentIndex = 0;
                return true;
            }
            haltMovement(client);
            state = State.STOPPED;
            currentIndex = 0;
            BooterClient.chat("Route complete.");
            return false;
        }
        currentIndex++;
        return true;
    }

    /** Direct straight-line walk toward the waypoint (fallback when A* finds no path). */
    private void directWalk(Minecraft client, LocalPlayer player, Waypoint target) {
        double px = player.getX();
        double pz = player.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-(target.x - px), target.z - pz));
        rotation.setTargetRotation(targetYaw, config.settings.customPitch);
        rotation.setActive(true);
        movement.tick(client, config.settings.holdSprint, config.settings.holdCrouch,
                config.settings.holdLeftClick, config.settings.autoJump);
    }

    private void haltMovement(Minecraft client) {
        movement.releaseAll(client);
        rotation.setActive(false);
        follower.clear();
    }
}
