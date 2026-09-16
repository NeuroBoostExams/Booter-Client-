package com.booter.client.zealot;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.pathfinder.Pathfinder;
import com.booter.client.rotation.RotationManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ZealotEmanFarmerModule {
    public enum State {
        IDLE, SCANNING, PATHING, ATTACKING
    }

    private static final String RESOURCE = "/booterclient/zealot/macrodetector_waypoint_profiles.json";
    private static final int SCAN_INTERVAL = 5;
    private static final int REPATH_INTERVAL = 45;
    private static final int FAILED_TARGET_BLACKLIST_TICKS = 80;
    private static final int PATH_MAX_NODES = 4000;
    private static final int PATH_MAX_RADIUS = 96;
    private static final double SCAN_RADIUS = 48.0;
    private static final double ATTACK_RANGE = 4.0;
    private static final double NODE_ELIGIBILITY_RADIUS = 4.5;
    private static final double VISIBLE_NODE_LOOKAHEAD = 5.0;
    private static final double MOB_AIM_LOS_RANGE = 5.0;
    private static final double APPROACH_DISTANCE = 2.2;
    private static final double PATH_GOAL_REACHED = 1.65;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower follower;
    private final List<Node> nodes = new ArrayList<>();

    private State state = State.IDLE;
    private Entity target;
    private Node targetNode;
    private int scanCooldown;
    private int repathCooldown;
    private boolean loaded;
    private BlockPos lastPathGoal;
    private int lastPathTargetId = -1;
    private final Map<Integer, Integer> failedTargets = new HashMap<>();

    public ZealotEmanFarmerModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
        this.follower = new PathFollower(movement, rotation);
    }

    public State getState() {
        return state;
    }

    public Entity getTarget() {
        return target;
    }

    public List<BlockPos> getPath() {
        return follower.getPath();
    }

    public int getPathIndex() {
        return follower.getIndex();
    }

    public int nodeCount() {
        ensureLoaded();
        return nodes.size();
    }

    public Node getTargetNode() {
        return targetNode;
    }

    public void start(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        ensureLoaded();
        if (nodes.isEmpty()) {
            BooterClient.chat("Zealot Eman Farmer: no hardcoded nodes loaded.");
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
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        target = null;
        targetNode = null;
        scanCooldown = 0;
        repathCooldown = 0;
        lastPathGoal = null;
        lastPathTargetId = -1;
        failedTargets.clear();
        follower.clear();
        state = State.SCANNING;
        BooterClient.chat("Zealot Eman Farmer started with " + nodes.size() + " node(s).");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        movement.releaseAll(client);
        rotation.setActive(false);
        follower.clear();
        target = null;
        targetNode = null;
        lastPathGoal = null;
        lastPathTargetId = -1;
        state = State.IDLE;
        BooterClient.chat("Zealot Eman Farmer stopped.");
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
        tickFailedTargets();

        if (!validTarget(target) || !eligible(target)) {
            target = null;
            targetNode = null;
            movement.setAttack(client, false);
            follower.clear();
            lastPathGoal = null;
            lastPathTargetId = -1;
            state = State.SCANNING;
            scanCooldown = 0;
        }

        if (target == null) {
            if (scanCooldown-- <= 0) {
                scanCooldown = SCAN_INTERVAL;
                target = findTarget(client, player);
                if (target != null) {
                    targetNode = nearestNode(target);
                    repathCooldown = 0;
                    beginPath(client, player);
                }
            }
            return;
        }

        if (canAttack(player, target)) {
            attackTarget(client, player);
            return;
        }
        if (canLookAtMob(player, target)) {
            chaseVisibleTarget(client, player);
            return;
        }

        movement.setAttack(client, false);
        if (state == State.ATTACKING || repathCooldown-- <= 0) {
            repathCooldown = REPATH_INTERVAL;
            beginPath(client, player);
            return;
        }

        if (state == State.PATHING) {
            PathFollower.Status status = follower.tick(client, false, false);
            if (status == PathFollower.Status.ARRIVED || status == PathFollower.Status.STUCK || status == PathFollower.Status.IDLE) {
                if (status == PathFollower.Status.STUCK && target != null) {
                    failedTargets.put(target.getId(), FAILED_TARGET_BLACKLIST_TICKS);
                    target = null;
                    targetNode = null;
                    lastPathGoal = null;
                    lastPathTargetId = -1;
                    state = State.SCANNING;
                    scanCooldown = 0;
                    return;
                }
                beginPath(client, player);
            }
        } else {
            beginPath(client, player);
        }

        if (target != null && canLookAtMob(player, target)) {
            aimAtTarget(player);
        }
    }

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
        return client.level.getEntities(player, box, this::validTarget).stream()
                .filter(e -> !failedTargets.containsKey(e.getId()))
                .filter(this::eligible)
                .min(Comparator
                        .comparingDouble((Entity e) -> nearestNodeDistanceSqr(e))
                        .thenComparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private void beginPath(Minecraft client, LocalPlayer player) {
        if (!validTarget(target) || !eligible(target)) {
            target = null;
            targetNode = null;
            lastPathGoal = null;
            lastPathTargetId = -1;
            state = State.SCANNING;
            return;
        }
        BlockPos goal = visibleNodeLookahead(client, player, target);
        if (goal == null) {
            goal = approachPos(player, target);
            for (BlockPos visible : visibleApproachGoals(client, target)) {
                goal = visible;
                break;
            }
        }
        if (player.blockPosition().distSqr(goal) <= PATH_GOAL_REACHED * PATH_GOAL_REACHED) {
            chaseVisibleTarget(client, player);
            return;
        }
        if (target.getId() == lastPathTargetId && goal.equals(lastPathGoal) && follower.isFollowing()) {
            state = State.PATHING;
            return;
        }
        Pathfinder land = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, true);
        List<BlockPos> path = land.findLocalPath(client.level, player.blockPosition(), goal);
        if (path != null && path.size() >= 2) {
            follower.setPath(path, player);
            lastPathGoal = goal;
            lastPathTargetId = target.getId();
            state = State.PATHING;
        } else {
            failedTargets.put(target.getId(), FAILED_TARGET_BLACKLIST_TICKS);
            target = null;
            targetNode = null;
            lastPathGoal = null;
            lastPathTargetId = -1;
            state = State.SCANNING;
            scanCooldown = SCAN_INTERVAL;
        }
    }

    private void attackTarget(Minecraft client, LocalPlayer player) {
        follower.clear();
        state = State.ATTACKING;
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        movement.tick(client, horiz > 2.1, false, false, false, true);
        aimAtTarget(player);
        movement.setUse(client, false);
        movement.setAttack(client, true);
    }

    private void chaseVisibleTarget(Minecraft client, LocalPlayer player) {
        follower.clear();
        lastPathGoal = null;
        lastPathTargetId = -1;
        state = State.PATHING;
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        aimAtTarget(player);
        movement.tick(client, horiz > 2.15, false, false, false, true);
        movement.setAttack(client, false);
        movement.setUse(client, false);
    }

    private void aimAtTarget(LocalPlayer player) {
        if (target == null) {
            return;
        }
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
    }

    private boolean validTarget(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity == Minecraft.getInstance().player) {
            return false;
        }
        return entity.getType() == EntityType.ENDERMAN
                && living.isAlive()
                && !living.isRemoved()
                && living.getHealth() > 0.0f;
    }

    private boolean eligible(Entity entity) {
        return nearestNodeDistanceSqr(entity) <= NODE_ELIGIBILITY_RADIUS * NODE_ELIGIBILITY_RADIUS;
    }

    private Node nearestNode(Entity entity) {
        Node best = null;
        double bestSq = Double.MAX_VALUE;
        for (Node node : nodes) {
            double dist = node.distanceSqr(entity);
            if (dist < bestSq) {
                bestSq = dist;
                best = node;
            }
        }
        return best;
    }

    private double nearestNodeDistanceSqr(Entity entity) {
        Node nearest = nearestNode(entity);
        return nearest == null ? Double.MAX_VALUE : nearest.distanceSqr(entity);
    }

    private void tickFailedTargets() {
        if (failedTargets.isEmpty()) {
            return;
        }
        failedTargets.entrySet().removeIf(entry -> entry.setValue(entry.getValue() - 1) <= 0);
    }

    private static BlockPos approachPos(LocalPlayer player, Entity mob) {
        double dx = player.getX() - mob.getX();
        double dz = player.getZ() - mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            float yaw = player.getYRot();
            double rad = Math.toRadians(yaw);
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
            len = 1.0;
        }
        double scale = APPROACH_DISTANCE / len;
        return BlockPos.containing(mob.getX() + dx * scale, mob.getY(), mob.getZ() + dz * scale);
    }

    private static boolean canAttack(LocalPlayer player, Entity entity) {
        return entity != null && player.distanceToSqr(entity) <= ATTACK_RANGE * ATTACK_RANGE && player.hasLineOfSight(entity);
    }

    private static boolean canLookAtMob(LocalPlayer player, Entity entity) {
        return entity != null
                && player.distanceToSqr(entity) <= MOB_AIM_LOS_RANGE * MOB_AIM_LOS_RANGE
                && player.hasLineOfSight(entity);
    }

    private BlockPos visibleNodeLookahead(Minecraft client, LocalPlayer player, Entity mob) {
        if (client.level == null || mob == null) {
            return null;
        }
        Node best = null;
        double bestPlayerSq = Double.MAX_VALUE;
        double maxNodeSq = VISIBLE_NODE_LOOKAHEAD * VISIBLE_NODE_LOOKAHEAD;
        for (Node node : nodes) {
            if (node.distanceSqr(mob) > maxNodeSq) {
                continue;
            }
            BlockPos pos = node.blockPos();
            if (!BaritonePathfinder.isStandable(client.level, pos) || !hasLineToNode(client, player, pos)) {
                continue;
            }
            double playerSq = pos.distSqr(player.blockPosition());
            if (playerSq < bestPlayerSq) {
                bestPlayerSq = playerSq;
                best = node;
            }
        }
        return best == null ? null : best.blockPos();
    }

    private static boolean hasLineToNode(Minecraft client, LocalPlayer player, BlockPos node) {
        if (client.level == null) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 point = new Vec3(node.getX() + 0.5, node.getY() + player.getEyeHeight(), node.getZ() + 0.5);
        HitResult hit = client.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static List<BlockPos> visibleApproachGoals(Minecraft client, Entity mob) {
        List<BlockPos> goals = new ArrayList<>(16);
        if (client.level == null) {
            return goals;
        }
        BlockPos center = mob.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double distSq = dx * dx + dz * dz;
                if (distSq < 4.0 || distSq > ATTACK_RANGE * ATTACK_RANGE) {
                    continue;
                }
                BlockPos candidate = center.offset(dx, 0, dz);
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos stand = candidate.offset(0, dy, 0);
                    if (client.level != null && BaritonePathfinder.isStandable(client.level, stand)) {
                        goals.add(stand);
                        break;
                    }
                }
            }
        }
        goals.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        return goals;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (var stream = ZealotEmanFarmerModule.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                BooterClient.LOGGER.error("Zealot waypoint resource missing: {}", RESOURCE);
                return;
            }
            Type type = new TypeToken<LinkedHashMap<String, List<Node>>>() {}.getType();
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, List<Node>> profiles = new Gson().fromJson(reader, type);
                if (profiles == null || profiles.isEmpty()) {
                    return;
                }
                List<Node> first = profiles.values().iterator().next();
                if (first != null) {
                    nodes.addAll(first);
                }
            }
        } catch (Exception e) {
            BooterClient.LOGGER.error("Failed to load Zealot waypoint profile", e);
        }
    }

    public static final class Node {
        public String id = "";
        public String name = "";
        public int x;
        public int y;
        public int z;
        public String dimension = "";

        double distanceSqr(Entity entity) {
            double dx = (x + 0.5) - entity.getX();
            double dy = y - entity.getY();
            double dz = (z + 0.5) - entity.getZ();
            return dx * dx + dy * dy + dz * dz;
        }

        public BlockPos blockPos() {
            return new BlockPos(x, y, z);
        }
    }
}
