package com.booter.client.turtlehunter;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.CoralRefillHelper;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.pathfinder.WaterPathfinder;
import com.booter.client.pathfinder.WaterPathFollower;
import com.booter.client.rotation.RotationManager;
import com.booter.client.waypoint.Waypoint;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turtle-only variant of Fish Hunter. It uses normal inputs, prefers water
 * paths, keeps a small standoff distance, and right-clicks when the turtle is
 * under the crosshair or inside the aimed block trace.
 */
public final class TurtleHunterModule {
    public enum State {
        IDLE, SCANNING, PATHING_WATER, PATHING_LAND, RETURNING, BACKING_OFF, ATTACKING
    }

    private static final Gson GSON = new Gson();
    private static final Type ROUTE_TYPE = new TypeToken<List<Waypoint>>() {}.getType();
    private static final String RETURN_ROUTE = "/booterclient/routes/return.json";
    private static final int SCAN_INTERVAL = 10;
    private static final int EMPTY_SCAN_INTERVAL = 40;
    private static final int EMPTY_SCANS_BEFORE_RETURN = 1;
    private static final int REPATH_INTERVAL = 20;
    private static final int ATTACK_TIMEOUT_TICKS = 60;
    private static final int BLACKLIST_TICKS = 300;
    private static final int PATH_MAX_NODES = 2600;
    private static final int PATH_MAX_RADIUS = 72;
    private static final double DEFAULT_SCAN_RADIUS = 48.0;
    private static final double MAX_TARGET_TRACK_RADIUS = 64.0;
    private static final double DIRECT_CHASE_RADIUS = 8.0;
    private static final double REPATH_MOVE_DISTANCE = 2.0;
    private static final double MIN_DISTANCE = 2.0;
    private static final double THROW_HITBOX_INFLATE = 4.0;
    private static final double AIM_LEAD_TICKS = 8.0;
    private static final double AIM_LEAD_MAX = 2.5;
    private static final int NO_TURTLE_SWAP_DELAY = 80;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower landFollower;
    private final WaterPathFollower waterFollower;
    private final List<Waypoint> returnRoute = new ArrayList<>();

    private State state = State.IDLE;
    private Entity target;
    private int scanCooldown;
    private int emptyScanCount;
    private int repathCooldown;
    private int attackTicks;
    private BlockPos lastPathGoal;
    private BlockPos lastTargetBlock;
    private boolean coralRefilling;
    private BlockPos coralTarget;
    private int coralRepathCooldown;
    private int noTurtleSwapTimer;
    private boolean returnRouteLoaded;
    private boolean returnCycleActive;
    private boolean returnWarpSent;
    private int returnRouteIndex;
    private final Map<Integer, Integer> blacklist = new HashMap<>();

    public TurtleHunterModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
        this.landFollower = new PathFollower(movement, rotation);
        this.waterFollower = new WaterPathFollower(movement, rotation);
    }

    public State getState() {
        return state;
    }

    public Entity getTarget() {
        return target;
    }

    public List<BlockPos> getPath() {
        return state == State.PATHING_WATER ? waterFollower.getPath() : landFollower.getPath();
    }

    public int getPathIndex() {
        return state == State.PATHING_WATER ? waterFollower.getIndex() : landFollower.getIndex();
    }

    public void start(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.autoFisher().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        target = null;
        scanCooldown = 0;
        emptyScanCount = 0;
        repathCooldown = 0;
        attackTicks = 0;
        lastPathGoal = null;
        lastTargetBlock = null;
        coralRefilling = false;
        coralTarget = null;
        coralRepathCooldown = 0;
        noTurtleSwapTimer = 0;
        returnCycleActive = false;
        returnWarpSent = false;
        returnRouteIndex = 0;
        blacklist.clear();
        landFollower.clear();
        waterFollower.clear();
        state = State.SCANNING;
        BooterClient.chat("Turtle Hunter started.");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        movement.releaseAll(client);
        rotation.setActive(false);
        landFollower.clear();
        waterFollower.clear();
        target = null;
        lastPathGoal = null;
        lastTargetBlock = null;
        coralRefilling = false;
        coralTarget = null;
        noTurtleSwapTimer = 0;
        returnCycleActive = false;
        returnWarpSent = false;
        returnRouteIndex = 0;
        state = State.IDLE;
        BooterClient.chat("Turtle Hunter stopped.");
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
        tickBlacklist();
        selectSlot(player);
        if (tickNoTurtleSwap(client, player)) {
            return;
        }

        if (CoralRefillHelper.needsRefill(player) || CoralRefillHelper.shouldKeepRefilling(player, coralRefilling)) {
            if (tickCoralRefill(client, player)) {
                return;
            }
        } else if (coralRefilling) {
            coralRefilling = false;
            coralTarget = null;
            coralRepathCooldown = 0;
            waterFollower.clear();
        }

        if (!validTarget(target)) {
            clearTarget(client);
            state = State.SCANNING;
        } else if (player.distanceToSqr(target) > MAX_TARGET_TRACK_RADIUS * MAX_TARGET_TRACK_RADIUS) {
            blacklist(target.getId());
            clearTarget(client);
            state = State.SCANNING;
            scanCooldown = 0;
        }

        if (target == null) {
            if (scanCooldown-- <= 0) {
                scanCooldown = SCAN_INTERVAL;
                target = findTarget(client, player);
                if (target != null) {
                    noTurtleSwapTimer = 0;
                    returnCycleActive = false;
                    returnWarpSent = false;
                    emptyScanCount = 0;
                    attackTicks = 0;
                    beginPath(client, player);
                } else {
                    scanCooldown = EMPTY_SCAN_INTERVAL;
                    if (++emptyScanCount >= EMPTY_SCANS_BEFORE_RETURN) {
                        startNoTurtleSwap(client);
                    }
                }
            }
            return;
        }

        double range = config.settings.turtleHunterDistance;
        double hDistSq = horizontalDistanceSqr(player, target);
        if (isWithinThrowZone(player, target, range)) {
            attackTarget(client, player);
            return;
        }
        if (hDistSq < MIN_DISTANCE * MIN_DISTANCE) {
            backAway(client, player);
            return;
        }
        if (hDistSq <= range * range) {
            attackTarget(client, player);
            return;
        }

        movement.setAttack(client, false);
        movement.setUse(client, false);
        if (hDistSq <= DIRECT_CHASE_RADIUS * DIRECT_CHASE_RADIUS && canDirectChase(player, target)) {
            chaseDirectly(client, player);
            return;
        }
        if (state == State.ATTACKING || shouldRepath(target) || repathCooldown-- <= 0) {
            repathCooldown = REPATH_INTERVAL;
            beginPath(client, player);
            return;
        }

        if (state == State.PATHING_WATER) {
            WaterPathFollower.Status status = waterFollower.tick(client, true);
            if (status == WaterPathFollower.Status.ARRIVED || status == WaterPathFollower.Status.STUCK || status == WaterPathFollower.Status.IDLE) {
                beginPath(client, player);
            }
        } else if (state == State.PATHING_LAND) {
            PathFollower.Status status = landFollower.tick(client, false, false, false);
            if (status == PathFollower.Status.ARRIVED || status == PathFollower.Status.STUCK || status == PathFollower.Status.IDLE) {
                beginPath(client, player);
            }
        } else {
            beginPath(client, player);
        }
        if (target != null && horizontalDistanceSqr(player, target) <= range * range) {
            attackTarget(client, player);
        }
    }

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(DEFAULT_SCAN_RADIUS);
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity entity : client.level.getEntities(player, box, TurtleHunterModule::isTurtle)) {
            if (!validTarget(entity)) {
                continue;
            }
            double sq = player.distanceToSqr(entity);
            if (sq < bestSq) {
                bestSq = sq;
                best = entity;
            }
        }
        return best;
    }

    private void startNoTurtleSwap(Minecraft client) {
        if (returnCycleActive || noTurtleSwapTimer > 0) {
            return;
        }
        movement.releaseAll(client);
        rotation.setActive(false);
        landFollower.clear();
        waterFollower.clear();
        BooterClient.runCommand(client, "hub");
        state = State.RETURNING;
        noTurtleSwapTimer = NO_TURTLE_SWAP_DELAY;
        scanCooldown = EMPTY_SCAN_INTERVAL;
        emptyScanCount = 0;
        returnCycleActive = true;
        returnWarpSent = false;
        returnRouteIndex = 0;
    }

    private boolean tickNoTurtleSwap(Minecraft client, LocalPlayer player) {
        if (noTurtleSwapTimer > 0 && --noTurtleSwapTimer == 0) {
            BooterClient.runCommand(client, "warp galatea");
            returnWarpSent = true;
            returnRouteIndex = 0;
            landFollower.clear();
            waterFollower.clear();
            movement.releaseAll(client);
        }
        if (!returnCycleActive) {
            return false;
        }
        state = State.RETURNING;
        if (!returnWarpSent || noTurtleSwapTimer > 0) {
            movement.releaseAll(client);
            rotation.setActive(false);
            return true;
        }
        tickReturnRoute(client, player);
        return true;
    }

    private void tickReturnRoute(Minecraft client, LocalPlayer player) {
        if (!ensureReturnRouteLoaded() || returnRoute.isEmpty()) {
            finishReturnCycle(client);
            return;
        }
        if (returnRouteIndex >= returnRoute.size()) {
            finishReturnCycle(client);
            return;
        }
        if (!landFollower.isFollowing()) {
            pathToReturnWaypoint(client, player);
            return;
        }
        PathFollower.Status status = landFollower.tick(client, false, false, false);
        if (status == PathFollower.Status.ARRIVED) {
            landFollower.clear();
            movement.releaseAll(client);
            returnRouteIndex++;
            if (returnRouteIndex >= returnRoute.size()) {
                finishReturnCycle(client);
            } else {
                pathToReturnWaypoint(client, player);
            }
        } else if (status == PathFollower.Status.STUCK || status == PathFollower.Status.IDLE) {
            landFollower.clear();
            pathToReturnWaypoint(client, player);
        }
    }

    private void pathToReturnWaypoint(Minecraft client, LocalPlayer player) {
        if (returnRouteIndex >= returnRoute.size()) {
            finishReturnCycle(client);
            return;
        }
        Waypoint wp = returnRoute.get(returnRouteIndex);
        if (wp.squaredDistanceTo(player.getX(), player.getY(), player.getZ()) <= 2.25) {
            returnRouteIndex++;
            landFollower.clear();
            movement.releaseAll(client);
            return;
        }
        BlockPos goal = BlockPos.containing(wp.x, wp.y, wp.z);
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, true);
        List<BlockPos> path = finder.findPath(client.level, player.blockPosition(), goal);
        if (path != null && path.size() >= 2) {
            waterFollower.clear();
            landFollower.setPath(path, player);
        } else {
            returnRouteIndex++;
            landFollower.clear();
        }
    }

    private void finishReturnCycle(Minecraft client) {
        movement.releaseAll(client);
        rotation.setActive(false);
        landFollower.clear();
        waterFollower.clear();
        target = null;
        lastPathGoal = null;
        lastTargetBlock = null;
        returnCycleActive = false;
        returnWarpSent = false;
        noTurtleSwapTimer = 0;
        scanCooldown = 0;
        emptyScanCount = 0;
        state = State.SCANNING;
    }

    private boolean ensureReturnRouteLoaded() {
        if (returnRouteLoaded) {
            return !returnRoute.isEmpty();
        }
        returnRouteLoaded = true;
        returnRoute.clear();
        try (var stream = TurtleHunterModule.class.getResourceAsStream(RETURN_ROUTE)) {
            if (stream == null) {
                BooterClient.LOGGER.error("Turtle Hunter return route missing: {}", RETURN_ROUTE);
                return false;
            }
            try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                List<Waypoint> loaded = GSON.fromJson(reader, ROUTE_TYPE);
                if (loaded != null) {
                    returnRoute.addAll(loaded);
                }
            }
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to load Turtle Hunter return route", e);
            return false;
        }
        return !returnRoute.isEmpty();
    }

    private boolean tickCoralRefill(Minecraft client, LocalPlayer player) {
        coralRefilling = true;
        movement.setAttack(client, false);
        movement.setUse(client, false);
        landFollower.clear();
        state = State.PATHING_WATER;

        if (coralTarget == null || CoralRefillHelper.reached(player, coralTarget)) {
            coralTarget = CoralRefillHelper.findNearest(client.level, player);
            coralRepathCooldown = 0;
        }
        if (coralTarget == null) {
            waterFollower.clear();
            movement.swim(client, false, false, true, false);
            return true;
        }
        if (coralRepathCooldown-- <= 0 || waterFollower.getPath().size() < 2) {
            coralRepathCooldown = REPATH_INTERVAL;
            WaterPathfinder finder = new WaterPathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS);
            BlockPos start = BlockPos.containing(player.getX(), player.getEyeY() - 0.7, player.getZ());
            List<BlockPos> path = finder.findPath(client.level, start, coralTarget);
            if (path != null && path.size() >= 2) {
                waterFollower.setPath(path, player);
            }
        }

        WaterPathFollower.Status status = waterFollower.tick(client, true);
        if (status == WaterPathFollower.Status.ARRIVED) {
            movement.swim(client, false, false, false, false);
        } else if (status == WaterPathFollower.Status.STUCK || status == WaterPathFollower.Status.IDLE) {
            coralTarget = CoralRefillHelper.findNearest(client.level, player);
            coralRepathCooldown = 0;
        }
        return true;
    }

    private void beginPath(Minecraft client, LocalPlayer player) {
        if (!validTarget(target)) {
            target = null;
            state = State.SCANNING;
            return;
        }
        BlockPos turtlePos = approachPos(player, target);
        BlockPos targetBlock = target.blockPosition();
        if (lastPathGoal != null && blockDistanceSqr(turtlePos, lastPathGoal) < REPATH_MOVE_DISTANCE * REPATH_MOVE_DISTANCE
                && (state == State.PATHING_WATER || state == State.PATHING_LAND) && !getPath().isEmpty()) {
            return;
        }
        lastPathGoal = turtlePos;
        lastTargetBlock = targetBlock;

        WaterPathfinder water = new WaterPathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS);
        BlockPos swimStart = BlockPos.containing(player.getX(), player.getEyeY() - 0.7, player.getZ());
        List<BlockPos> waterPath = water.findPath(client.level, swimStart, turtlePos);
        if (waterPath != null && waterPath.size() >= 2) {
            landFollower.clear();
            waterFollower.setPath(waterPath, player);
            state = State.PATHING_WATER;
            return;
        }

        Pathfinder land = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, true);
        List<BlockPos> landPath = land.findPath(client.level, player.blockPosition(), turtlePos);
        if (landPath != null && landPath.size() >= 2) {
            waterFollower.clear();
            landFollower.setPath(landPath, player);
            state = State.PATHING_LAND;
            return;
        }

        if (canDirectChase(player, target)) {
            chaseDirectly(client, player);
        } else {
            blacklist(target.getId());
            clearTarget(client);
            state = State.SCANNING;
            scanCooldown = 0;
        }
    }

    private void attackTarget(Minecraft client, LocalPlayer player) {
        landFollower.clear();
        waterFollower.clear();
        state = State.ATTACKING;

        Vec3 aim = leadAimPoint(target);
        double tx = aim.x;
        double ty = aim.y;
        double tz = aim.z;
        double dx = tx - player.getX();
        double dy = ty - player.getEyeY();
        double dz = tz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);

        boolean aimed = isWithinThrowZone(player, target, config.settings.turtleHunterDistance)
                || lookRayTouchesTarget(player, target, Math.max(config.settings.turtleHunterDistance + THROW_HITBOX_INFLATE, MIN_DISTANCE + THROW_HITBOX_INFLATE));
        attackTicks = aimed ? attackTicks + 1 : Math.max(0, attackTicks - 1);
        if (attackTicks > ATTACK_TIMEOUT_TICKS) {
            blacklist(target.getId());
            clearTarget(client);
            state = State.SCANNING;
            scanCooldown = 0;
            return;
        }

        boolean rise = dy > 0.35;
        boolean descend = dy < -0.35 && !player.onGround();
        boolean forward = horiz > MIN_DISTANCE - 0.25;
        if (player.isInWater()) {
            movement.swim(client, forward, false, rise, descend);
        } else {
            movement.move(client, forward, false, false, false, descend, true);
        }
        movement.setAttack(client, false);
        movement.setUse(client, config.settings.turtleHunterRightClick && aimed);
    }

    private void clearTarget(Minecraft client) {
        target = null;
        attackTicks = 0;
        movement.setAttack(client, false);
        movement.setUse(client, false);
        landFollower.clear();
        waterFollower.clear();
        lastPathGoal = null;
        lastTargetBlock = null;
    }

    private void chaseDirectly(Minecraft client, LocalPlayer player) {
        if (!validTarget(target)) {
            target = null;
            state = State.SCANNING;
            scanCooldown = 0;
            return;
        }
        landFollower.clear();
        waterFollower.clear();
        state = player.isInWater() ? State.PATHING_WATER : State.PATHING_LAND;

        Vec3 aim = leadAimPoint(target);
        double tx = aim.x;
        double ty = aim.y;
        double tz = aim.z;
        double dx = tx - player.getX();
        double dy = ty - player.getEyeY();
        double dz = tz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);

        boolean rise = dy > 0.35;
        boolean descend = dy < -0.35 && !player.onGround();
        boolean forward = horiz > MIN_DISTANCE - 0.25;
        if (player.isInWater()) {
            movement.swim(client, forward, false, rise, descend);
        } else {
            movement.move(client, forward, false, false, false, descend, true);
        }
        movement.setAttack(client, false);
        movement.setUse(client, false);
        repathCooldown = REPATH_INTERVAL;
    }

    private boolean shouldRepath(Entity entity) {
        if (lastTargetBlock == null || entity == null) {
            return true;
        }
        BlockPos current = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
        return blockDistanceSqr(current, lastTargetBlock) >= REPATH_MOVE_DISTANCE * REPATH_MOVE_DISTANCE;
    }

    private void backAway(Minecraft client, LocalPlayer player) {
        landFollower.clear();
        waterFollower.clear();
        state = State.BACKING_OFF;
        attackTicks = 0;
        movement.setAttack(client, false);
        movement.setUse(client, false);
        movement.setCrouch(client, false);

        Vec3 aim = leadAimPoint(target);
        double dx = aim.x - player.getX();
        double dy = aim.y - player.getEyeY();
        double dz = aim.z - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);

        movement.move(client, false, true, false, false, false, false);
    }

    private void selectSlot(LocalPlayer player) {
        int slot = Mth.clamp(config.settings.turtleHunterSlot, 1, 8) - 1;
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }

    private static Vec3 leadAimPoint(Entity entity) {
        Vec3 center = entity.getBoundingBox().getCenter();
        Vec3 velocity = entity.getDeltaMovement();
        double leadX = Mth.clamp(velocity.x * AIM_LEAD_TICKS, -AIM_LEAD_MAX, AIM_LEAD_MAX);
        double leadY = Mth.clamp(velocity.y * AIM_LEAD_TICKS, -AIM_LEAD_MAX * 0.5, AIM_LEAD_MAX * 0.5);
        double leadZ = Mth.clamp(velocity.z * AIM_LEAD_TICKS, -AIM_LEAD_MAX, AIM_LEAD_MAX);
        return center.add(leadX, leadY, leadZ);
    }

    private boolean validTarget(Entity entity) {
        return entity != null && entity.isAlive() && !entity.isRemoved()
                && !blacklist.containsKey(entity.getId()) && isTurtle(entity);
    }

    private static boolean isTurtle(Entity entity) {
        return entity.getType() == EntityType.TURTLE;
    }

    private static boolean isSurfaceTarget(Minecraft client, Entity entity) {
        if (client.level == null || entity == null) {
            return false;
        }
        AABB box = entity.getBoundingBox();
        BlockPos center = BlockPos.containing(entity.getX(), (box.minY + box.maxY) * 0.5, entity.getZ());
        BlockPos top = BlockPos.containing(entity.getX(), box.maxY, entity.getZ());
        boolean centerWater = client.level.getFluidState(center).is(FluidTags.WATER);
        boolean topWater = client.level.getFluidState(top).is(FluidTags.WATER);
        boolean aboveWater = client.level.getFluidState(top.above()).is(FluidTags.WATER);
        return !centerWater || !topWater || !aboveWater;
    }

    private static boolean canDirectChase(LocalPlayer player, Entity entity) {
        return entity != null && (player.hasLineOfSight(entity) || player.distanceToSqr(entity) <= 9.0);
    }

    private static double horizontalDistanceSqr(LocalPlayer player, Entity entity) {
        double dx = player.getX() - entity.getX();
        double dz = player.getZ() - entity.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean lookRayTouchesTarget(LocalPlayer player, Entity entity, double reach) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(reach));
        if (entity.getBoundingBox().inflate(THROW_HITBOX_INFLATE).clip(start, end).isPresent()) {
            return true;
        }
        BlockPos block = entity.blockPosition();
        int steps = Math.max(4, (int) Math.ceil(reach * 4.0));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            BlockPos p = BlockPos.containing(
                    Mth.lerp(t, start.x, end.x),
                    Mth.lerp(t, start.y, end.y),
                    Mth.lerp(t, start.z, end.z));
            if (p.equals(block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inExpandedHitbox(LocalPlayer player, Entity entity) {
        AABB box = entity.getBoundingBox().inflate(THROW_HITBOX_INFLATE);
        return player.getBoundingBox().intersects(box);
    }

    private static boolean isWithinThrowZone(LocalPlayer player, Entity entity, double range) {
        if (entity == null) {
            return false;
        }
        if (inExpandedHitbox(player, entity)) {
            return true;
        }
        AABB box = entity.getBoundingBox().inflate(THROW_HITBOX_INFLATE);
        double px = Mth.clamp(player.getX(), box.minX, box.maxX);
        double py = Mth.clamp(player.getEyeY(), box.minY, box.maxY);
        double pz = Mth.clamp(player.getZ(), box.minZ, box.maxZ);
        double dx = player.getX() - px;
        double dy = player.getEyeY() - py;
        double dz = player.getZ() - pz;
        double allowed = Math.max(range, THROW_HITBOX_INFLATE);
        return dx * dx + dy * dy + dz * dz <= allowed * allowed;
    }

    private BlockPos approachPos(LocalPlayer player, Entity turtle) {
        double dx = player.getX() - turtle.getX();
        double dz = player.getZ() - turtle.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            float yaw = player.getYRot();
            double rad = Math.toRadians(yaw);
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
            len = 1.0;
        }
        double scale = MIN_DISTANCE / len;
        return BlockPos.containing(turtle.getX() + dx * scale, turtle.getY(), turtle.getZ() + dz * scale);
    }

    private static double blockDistanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void blacklist(int entityId) {
        blacklist.put(entityId, BLACKLIST_TICKS);
    }

    private void tickBlacklist() {
        blacklist.entrySet().removeIf(e -> {
            int left = e.getValue() - 1;
            e.setValue(left);
            return left <= 0;
        });
    }
}
