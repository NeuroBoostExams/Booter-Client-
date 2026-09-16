package com.booter.client.fishhunter;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds fish mobs, moves into configurable melee range, aims smoothly at them
 * and holds the normal attack key until the entity is gone. Movement uses the
 * existing land and water path followers, preferring the water pathfinder because
 * the targets are usually submerged.
 */
public final class FishHunterModule {
    public enum State {
        IDLE, SCANNING, PATHING_WATER, PATHING_LAND, BACKING_OFF, ATTACKING
    }

    private static final int SCAN_INTERVAL = 10;
    private static final int REPATH_INTERVAL = 20;
    private static final int ATTACK_TIMEOUT_TICKS = 60;
    private static final int BLACKLIST_TICKS = 300;
    private static final int PATH_MAX_NODES = 12000;
    private static final int PATH_MAX_RADIUS = 224;
    private static final double DEFAULT_SCAN_RADIUS = 48.0;
    private static final double DIRECT_CHASE_RADIUS = 14.0;
    private static final double REPATH_MOVE_DISTANCE = 2.0;
    private static final double MIN_DISTANCE = 2.0;
    private static final float AIM_TOLERANCE = 12.0f;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower landFollower;
    private final WaterPathFollower waterFollower;

    private State state = State.IDLE;
    private Entity target;
    private int scanCooldown;
    private int repathCooldown;
    private int attackTicks;
    private BlockPos lastPathGoal;
    private BlockPos lastTargetBlock;
    private boolean coralRefilling;
    private BlockPos coralTarget;
    private int coralRepathCooldown;
    private final Map<Integer, Integer> blacklist = new HashMap<>();

    public FishHunterModule(ConfigManager config, MovementController movement, RotationManager rotation) {
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
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        target = null;
        scanCooldown = 0;
        repathCooldown = 0;
        attackTicks = 0;
        lastPathGoal = null;
        lastTargetBlock = null;
        coralRefilling = false;
        coralTarget = null;
        coralRepathCooldown = 0;
        blacklist.clear();
        landFollower.clear();
        waterFollower.clear();
        state = State.SCANNING;
        BooterClient.chat("Fish Hunter started.");
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
        state = State.IDLE;
        BooterClient.chat("Fish Hunter stopped.");
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
            target = null;
            attackTicks = 0;
            movement.setAttack(client, false);
            movement.setUse(client, false);
            landFollower.clear();
            waterFollower.clear();
            lastPathGoal = null;
            lastTargetBlock = null;
            state = State.SCANNING;
        }

        if (target == null) {
            if (scanCooldown-- <= 0) {
                scanCooldown = SCAN_INTERVAL;
                target = findTarget(client, player);
                if (target != null) {
                    attackTicks = 0;
                    beginPath(client, player);
                }
            }
            return;
        }

        double range = config.settings.fishHunterDistance;
        double hDistSq = horizontalDistanceSqr(player, target);
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
        if (hDistSq <= DIRECT_CHASE_RADIUS * DIRECT_CHASE_RADIUS) {
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
    }

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        double radius = DEFAULT_SCAN_RADIUS;
        AABB box = player.getBoundingBox().inflate(radius);
        return client.level.getEntities(player, box, FishHunterModule::isFish).stream()
                .filter(this::validTarget)
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
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
        BlockPos fishPos = approachPos(player, target);
        BlockPos targetBlock = target.blockPosition();
        if (lastPathGoal != null && blockDistanceSqr(fishPos, lastPathGoal) < REPATH_MOVE_DISTANCE * REPATH_MOVE_DISTANCE
                && (state == State.PATHING_WATER || state == State.PATHING_LAND) && !getPath().isEmpty()) {
            chaseDirectly(client, player);
            return;
        }
        lastPathGoal = fishPos;
        lastTargetBlock = targetBlock;

        WaterPathfinder water = new WaterPathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS);
        BlockPos swimStart = BlockPos.containing(player.getX(), player.getEyeY() - 0.7, player.getZ());
        List<BlockPos> waterPath = water.findPath(client.level, swimStart, fishPos);
        if (waterPath != null && waterPath.size() >= 2) {
            landFollower.clear();
            waterFollower.setPath(waterPath, player);
            state = State.PATHING_WATER;
            return;
        }

        Pathfinder land = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, true);
        List<BlockPos> landPath = land.findPath(client.level, player.blockPosition(), fishPos);
        if (landPath != null && landPath.size() >= 2) {
            waterFollower.clear();
            landFollower.setPath(landPath, player);
            state = State.PATHING_LAND;
            return;
        }

        state = State.SCANNING;
        scanCooldown = SCAN_INTERVAL;
    }

    private boolean shouldRepath(Entity entity) {
        if (lastTargetBlock == null || entity == null) {
            return true;
        }
        BlockPos current = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
        return blockDistanceSqr(current, lastTargetBlock) >= REPATH_MOVE_DISTANCE * REPATH_MOVE_DISTANCE;
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

        double tx = target.getX();
        double ty = target.getBoundingBox().getCenter().y;
        double tz = target.getZ();
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
        boolean forward = horiz > MIN_DISTANCE + 0.15;
        if (player.isInWater()) {
            movement.swim(client, forward, false, rise, descend);
        } else {
            movement.move(client, forward, false, false, false, descend, true);
        }
        movement.setAttack(client, false);
        movement.setUse(client, false);
        repathCooldown = REPATH_INTERVAL;
    }

    private void attackTarget(Minecraft client, LocalPlayer player) {
        landFollower.clear();
        waterFollower.clear();
        state = State.ATTACKING;

        double tx = target.getX();
        double ty = target.getBoundingBox().getCenter().y;
        double tz = target.getZ();
        double dx = tx - player.getX();
        double dy = ty - player.getEyeY();
        double dz = tz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);

        double yawError = Math.abs(Mth.wrapDegrees((double) yaw - player.getYRot()));
        double pitchError = Math.abs(pitch - player.getXRot());
        boolean aimed = (yawError <= AIM_TOLERANCE && pitchError <= AIM_TOLERANCE)
                || lookRayTouchesFish(player, target, Math.max(config.settings.fishHunterDistance, MIN_DISTANCE + 1.0));
        if (aimed) {
            attackTicks++;
        } else {
            attackTicks = Math.max(0, attackTicks - 1);
        }
        if (attackTicks >= ATTACK_TIMEOUT_TICKS) {
            blacklist(target.getId());
            target = null;
            attackTicks = 0;
            movement.setAttack(client, false);
            movement.setUse(client, false);
            movement.setCrouch(client, false);
            state = State.SCANNING;
            scanCooldown = 0;
            return;
        }
        boolean rise = dy > 0.35;
        boolean descend = dy < -0.35 && !player.onGround();
        boolean forward = horiz > MIN_DISTANCE + 0.15;
        if (player.isInWater()) {
            movement.swim(client, forward, false, rise, descend);
        } else {
            movement.move(client, forward, false, false, false, descend, true);
        }
        movement.setAttack(client, false);
        movement.setUse(client, aimed);
    }

    private void backAway(Minecraft client, LocalPlayer player) {
        landFollower.clear();
        waterFollower.clear();
        state = State.BACKING_OFF;
        attackTicks = 0;
        movement.setAttack(client, false);
        movement.setUse(client, false);
        movement.setCrouch(client, false);

        double dx = target.getX() - player.getX();
        double dy = target.getBoundingBox().getCenter().y - player.getEyeY();
        double dz = target.getZ() - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);

        movement.move(client, false, true, false, false, false, false);
    }

    private void selectSlot(LocalPlayer player) {
        int slot = Mth.clamp(config.settings.fishHunterSlot, 1, 8) - 1;
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }

    private boolean validTarget(Entity entity) {
        return entity != null && entity.isAlive() && !entity.isRemoved()
                && !blacklist.containsKey(entity.getId()) && isFish(entity);
    }

    private static double horizontalDistanceSqr(LocalPlayer player, Entity entity) {
        double dx = player.getX() - entity.getX();
        double dz = player.getZ() - entity.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean lookRayTouchesFish(LocalPlayer player, Entity fish, double reach) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(reach));
        if (fish.getBoundingBox().inflate(0.75).clip(start, end).isPresent()) {
            return true;
        }
        BlockPos fishBlock = fish.blockPosition();
        int steps = Math.max(4, (int) Math.ceil(reach * 4.0));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            BlockPos p = BlockPos.containing(
                    Mth.lerp(t, start.x, end.x),
                    Mth.lerp(t, start.y, end.y),
                    Mth.lerp(t, start.z, end.z));
            if (p.equals(fishBlock)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canSeeOrClose(LocalPlayer player, Entity entity) {
        return player.hasLineOfSight(entity) || player.distanceToSqr(entity) <= 9.0;
    }

    private static double blockDistanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isFish(Entity entity) {
        EntityType<?> type = entity.getType();
        return type == EntityType.COD || type == EntityType.SALMON || type == EntityType.TROPICAL_FISH;
    }

    private BlockPos approachPos(LocalPlayer player, Entity fish) {
        double dx = player.getX() - fish.getX();
        double dz = player.getZ() - fish.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            float yaw = player.getYRot();
            double rad = Math.toRadians(yaw);
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
            len = 1.0;
        }
        double scale = MIN_DISTANCE / len;
        return BlockPos.containing(fish.getX() + dx * scale, fish.getY(), fish.getZ() + dz * scale);
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
