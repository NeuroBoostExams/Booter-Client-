package com.booter.client.azalea;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.rotation.RotationManager;
import com.booter.client.waypoint.Waypoint;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Scans flowering azalea blocks into a queue, paths to a nearby standable spot,
 * pre-rotates during the final approach, and breaks the block using the normal
 * attack key as soon as the crosshair reaches it.
 */
public final class FloweringAzaleaFarmerModule {
    public enum State {
        IDLE, SCANNING, WALKING, ROUTING, ROTATING, MINING
    }

    private static final Gson GSON = new Gson();
    private static final Type ROUTE_TYPE = new TypeToken<List<Waypoint>>() {}.getType();
    private static final String PATROL_ROUTE = "/booterclient/routes/azaela.json";
    private static final int PATH_MAX_NODES = 8000;
    private static final int PATH_MAX_RADIUS = 96;
    private static final int SCAN_INTERVAL = 10;
    private static final int ROTATE_TIMEOUT_TICKS = 30;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower follower;
    private final List<BlockPos> queue = new ArrayList<>();
    private final List<Waypoint> patrolRoute = new ArrayList<>();

    private State state = State.IDLE;
    private BlockPos target;
    private int scanTimer;
    private int rotateTicks;
    private int routeIndex;
    private boolean routeLoaded;

    public FloweringAzaleaFarmerModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
        this.follower = new PathFollower(movement, rotation);
    }

    public State getState() {
        return state;
    }

    public BlockPos getTarget() {
        return target;
    }

    public List<BlockPos> getPath() {
        return follower.getPath();
    }

    public int getPathIndex() {
        return follower.getIndex();
    }

    public int queuedCount() {
        return queue.size();
    }

    public void start(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.autoFisher().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        queue.clear();
        target = null;
        scanTimer = 0;
        rotateTicks = 0;
        routeIndex = -1;
        follower.clear();
        ensureRouteLoaded();
        state = State.SCANNING;
        BooterClient.chat("Flowering Azalea Farmer started.");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        movement.releaseAll(client);
        rotation.setActive(false);
        follower.clear();
        target = null;
        rotateTicks = 0;
        routeIndex = -1;
        state = State.IDLE;
        BooterClient.chat("Flowering Azalea Farmer stopped.");
    }

    public void toggle(Minecraft client) {
        if (state == State.IDLE) {
            start(client);
        } else {
            stop(client);
        }
    }

    public void tick(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            stop(client);
            return;
        }

        pruneQueue(client);
        if (scanTimer-- <= 0) {
            scanTimer = Math.max(1, config.settings.azaleaScanInterval);
            scan(client, player);
        }

        if (target != null && !isFlowering(client, target)) {
            target = null;
            follower.clear();
            rotateTicks = 0;
            state = State.SCANNING;
        }

        if (target == null) {
            target = nextTarget(player);
            if (target != null) {
                follower.clear();
                rotateTicks = 0;
                beginPath(client, player);
                return;
            }
            movement.setAttack(client, false);
            movement.setUse(client, false);
            tickRoute(client, player);
            return;
        }

        if (state == State.MINING) {
            mine(client, player);
            return;
        }

        movement.setAttack(client, false);
        if (state == State.ROTATING) {
            rotateThenMine(client, player);
            return;
        }

        if (!follower.isFollowing()) {
            beginPath(client, player);
            return;
        }
        state = State.WALKING;
        PathFollower.Status status = follower.tick(client, false, false, false);
        if (status == PathFollower.Status.ARRIVED) {
            stopAtLastNodeAndRotate(client, player);
        } else if (status == PathFollower.Status.STUCK || status == PathFollower.Status.IDLE) {
            beginPath(client, player);
        }
    }

    private void scan(Minecraft client, LocalPlayer player) {
        int r = config.settings.azaleaScanRadius;
        int vr = Math.min(r, 8);
        int rSq = r * r;
        BlockPos base = player.blockPosition();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -vr; dy <= vr; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > rSq) {
                        continue;
                    }
                    BlockPos p = base.offset(dx, dy, dz);
                    if (isFlowering(client, p) && !queue.contains(p) && !p.equals(target)) {
                        queue.add(p.immutable());
                    }
                }
            }
        }
        queue.sort(Comparator.comparingDouble(p -> distanceSqToBlock(player, p)));
    }

    private void pruneQueue(Minecraft client) {
        queue.removeIf(p -> !isFlowering(client, p));
    }

    private BlockPos nextTarget(LocalPlayer player) {
        if (queue.isEmpty()) {
            return null;
        }
        queue.sort(Comparator.comparingDouble(p -> distanceSqToBlock(player, p)));
        Iterator<BlockPos> it = queue.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            it.remove();
            return p;
        }
        return null;
    }

    private void beginPath(Minecraft client, LocalPlayer player) {
        if (target == null || !isFlowering(client, target)) {
            target = null;
            state = State.SCANNING;
            return;
        }
        List<BlockPos> goals = approachGoals(client, target);
        if (goals.isEmpty()) {
            stopAtLastNodeAndRotate(client, player);
            return;
        }
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> path = finder.findPath(client.level, player.blockPosition(), goals);
        if (path == null || path.size() < 2) {
            stopAtLastNodeAndRotate(client, player);
            return;
        }
        follower.setPath(path, player);
        state = State.WALKING;
    }

    private void stopAtLastNodeAndRotate(Minecraft client, LocalPlayer player) {
        follower.clear();
        movement.releaseAll(client);
        movement.setAttack(client, false);
        rotateTicks = 0;
        state = State.ROTATING;
        rotateThenMine(client, player);
    }

    private void rotateThenMine(Minecraft client, LocalPlayer player) {
        follower.clear();
        movement.releaseAll(client);
        movement.setAttack(client, false);
        state = State.ROTATING;
        aimAt(player, target);

        if (isCrosshairOnTarget(client) || ++rotateTicks >= ROTATE_TIMEOUT_TICKS) {
            rotateTicks = 0;
            state = State.MINING;
            mine(client, player);
        }
    }

    private List<BlockPos> approachGoals(Minecraft client, BlockPos block) {
        List<BlockPos> out = new ArrayList<>();
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos feet = new BlockPos(block.getX() + dx, block.getY(), block.getZ() + dz);
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = feet.above(dy);
                        if (BaritonePathfinder.isStandable(client.level, p)) {
                            out.add(p);
                        }
                    }
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return out;
    }

    private void tickRoute(Minecraft client, LocalPlayer player) {
        if (!ensureRouteLoaded() || patrolRoute.isEmpty()) {
            state = State.SCANNING;
            movement.releaseAll(client);
            return;
        }
        state = State.ROUTING;
        if (!follower.isFollowing()) {
            if (routeIndex < 0) {
                routeIndex = closestRouteIndex(player);
            }
            pathToRouteWaypoint(client, player);
            return;
        }

        PathFollower.Status status = follower.tick(client, false, false, false);
        if (status == PathFollower.Status.ARRIVED) {
            follower.clear();
            movement.releaseAll(client);
            movement.setAttack(client, false);
            scan(client, player);
            target = nextTarget(player);
            if (target != null) {
                beginPath(client, player);
                return;
            }
            routeIndex = (routeIndex + 1) % patrolRoute.size();
            pathToRouteWaypoint(client, player);
        } else if (status == PathFollower.Status.STUCK || status == PathFollower.Status.IDLE) {
            pathToRouteWaypoint(client, player);
        }
    }

    private void pathToRouteWaypoint(Minecraft client, LocalPlayer player) {
        if (patrolRoute.isEmpty()) {
            state = State.SCANNING;
            return;
        }
        routeIndex = Mth.clamp(routeIndex, 0, patrolRoute.size() - 1);
        Waypoint wp = patrolRoute.get(routeIndex);
        BlockPos goal = BlockPos.containing(wp.x, wp.y, wp.z);
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> path = finder.findPath(client.level, player.blockPosition(), goal);
        if (path == null || path.size() < 2) {
            routeIndex = (routeIndex + 1) % patrolRoute.size();
            state = State.ROUTING;
            return;
        }
        follower.setPath(path, player);
        state = State.ROUTING;
    }

    private int closestRouteIndex(LocalPlayer player) {
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < patrolRoute.size(); i++) {
            Waypoint wp = patrolRoute.get(i);
            double sq = distanceSqToWaypoint(player, wp);
            if (sq < bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        return best;
    }

    private boolean ensureRouteLoaded() {
        if (routeLoaded) {
            return !patrolRoute.isEmpty();
        }
        routeLoaded = true;
        patrolRoute.clear();
        try (var stream = FloweringAzaleaFarmerModule.class.getResourceAsStream(PATROL_ROUTE)) {
            if (stream == null) {
                BooterClient.LOGGER.error("Flowering Azalea route missing: {}", PATROL_ROUTE);
                return false;
            }
            try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                List<Waypoint> loaded = GSON.fromJson(reader, ROUTE_TYPE);
                if (loaded != null) {
                    patrolRoute.addAll(loaded);
                }
            }
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to load Flowering Azalea route", e);
            return false;
        }
        return !patrolRoute.isEmpty();
    }

    private void mine(Minecraft client, LocalPlayer player) {
        follower.clear();
        movement.releaseAll(client);
        state = State.MINING;
        aimAt(player, target);

        boolean aimed = isCrosshairOnTarget(client);
        movement.setAttack(client, aimed);
        if (!isFlowering(client, target)) {
            movement.setAttack(client, false);
            target = null;
            rotateTicks = 0;
            state = State.SCANNING;
        }
    }

    private boolean isCrosshairOnTarget(Minecraft client) {
        return client.hitResult instanceof BlockHitResult hit
                && client.hitResult.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    private void aimAt(LocalPlayer player, BlockPos block) {
        double tx = block.getX() + 0.5;
        double ty = block.getY() + 0.45;
        double tz = block.getZ() + 0.5;
        double dx = tx - player.getX();
        double dy = ty - player.getEyeY();
        double dz = tz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
    }

    private boolean isFlowering(Minecraft client, BlockPos pos) {
        return client.level != null && client.level.getBlockState(pos).is(Blocks.FLOWERING_AZALEA);
    }

    private static double distanceSqToBlock(LocalPlayer player, BlockPos block) {
        double dx = block.getX() + 0.5 - player.getX();
        double dy = block.getY() + 0.5 - player.getEyeY();
        double dz = block.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceSqToWaypoint(LocalPlayer player, Waypoint wp) {
        double dx = wp.x - player.getX();
        double dy = wp.y - player.getY();
        double dz = wp.z - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
