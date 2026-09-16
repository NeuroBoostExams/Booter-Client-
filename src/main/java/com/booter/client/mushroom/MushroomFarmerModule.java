package com.booter.client.mushroom;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds nearby red/brown mushrooms (vanilla blocks, read from the client's own
 * loaded world — no server cues), pathfinds to one, looks at it for a
 * configurable dwell (default 3.25s) and then mines it by holding left-click,
 * then moves on to the next. Uses only vanilla movement/attack inputs and the
 * shared smooth rotation. Entirely client-side and target-agnostic — it only
 * acts on the mushroom blocks present in your world.
 */
public final class MushroomFarmerModule {
    public enum State {
        IDLE, SEEKING, WALKING, LOOKING, MINING
    }

    /** Distance to the mushroom at which we stop walking and start looking. */
    private static final double REACH = 4.0;
    private static final double NODE_REACH = 0.65;
    private static final int PATH_MAX_NODES = 8000;
    private static final int PATH_MAX_RADIUS = 96;
    private static final int SCAN_VERTICAL = 6;
    private static final int STUCK_TICKS = 60;
    private static final int MINE_TIMEOUT_TICKS = 200;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;

    private State state = State.IDLE;
    private BlockPos target;
    private List<BlockPos> path = Collections.emptyList();
    private int pathIndex;
    private long lookStartNanos;
    private int stuckTicks;
    private int mineTicks;
    private double lastDistSq;
    private final Set<BlockPos> skip = new HashSet<>();

    public MushroomFarmerModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
    }

    public State getState() {
        return state;
    }

    public BlockPos getTarget() {
        return target;
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public int getPathIndex() {
        return pathIndex;
    }

    public void start(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);
        skip.clear();
        target = null;
        state = State.SEEKING;
        BooterClient.chat("Mushroom Farmer started.");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        halt(client);
        BooterClient.chat("Mushroom Farmer stopped.");
    }

    public void toggle(Minecraft client) {
        if (state == State.IDLE) {
            start(client);
        } else {
            stop(client);
        }
    }

    /** Runs every client tick. */
    public void tick(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            halt(client);
            return;
        }

        switch (state) {
            case SEEKING -> tickSeeking(client, player, level);
            case WALKING -> tickWalking(client, player, level);
            case LOOKING -> tickLooking(client, player, level);
            case MINING -> tickMining(client, player, level);
            default -> {
            }
        }
    }

    private void tickSeeking(Minecraft client, LocalPlayer player, ClientLevel level) {
        movement.setAttack(client, false);
        target = findNearestMushroom(level, player.blockPosition(), config.settings.mushroomScanRadius);
        if (target == null) {
            BooterClient.chat("Mushroom Farmer: no more reachable mushrooms in range.");
            halt(client);
            return;
        }
        if (distToTargetSq(player) <= REACH * REACH) {
            beginLooking();
            return;
        }
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> p = finder.findPath(level, player.blockPosition(), target);
        if (p == null || p.size() < 2) {
            skip.add(target); // unreachable; try another next tick
            target = null;
            return;
        }
        path = p;
        pathIndex = 1;
        stuckTicks = 0;
        lastDistSq = Double.MAX_VALUE;
        state = State.WALKING;
    }

    private void tickWalking(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (target == null || !isMushroom(level.getBlockState(target))) {
            // Mushroom gone (e.g. broken by something else) — look for another.
            nextTarget(client);
            return;
        }
        if (distToTargetSq(player) <= REACH * REACH) {
            beginLooking();
            return;
        }
        if (pathIndex >= path.size()) {
            if (!repath(client, player, level)) {
                skipTarget(client);
            }
            return;
        }

        BlockPos node = path.get(pathIndex);
        double cx = node.getX() + 0.5;
        double cz = node.getZ() + 0.5;
        double dx = cx - player.getX();
        double dz = cz - player.getZ();
        double horizSq = dx * dx + dz * dz;

        if (horizSq <= NODE_REACH * NODE_REACH && Math.abs(node.getY() - player.getY()) <= 1.25) {
            pathIndex++;
            stuckTicks = 0;
            lastDistSq = Double.MAX_VALUE;
            return;
        }

        if (horizSq < lastDistSq - 0.01) {
            lastDistSq = horizSq;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            if (!repath(client, player, level)) {
                skipTarget(client);
            }
            return;
        }

        // Walk toward the node: gentle 0-10° pitch, auto-jump for hills/steps.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.sqrt(horizSq);
        double dyEye = (node.getY() + 0.6) - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.1, horiz))), 0.0, 10.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
        movement.tick(client, false, false, false, true);
    }

    private void tickLooking(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (target == null || !isMushroom(level.getBlockState(target))) {
            nextTarget(client);
            return;
        }
        aimAtTarget(player, level);
        long dwellNanos = (long) (config.settings.mushroomLookSeconds * 1.0e9);
        if (System.nanoTime() - lookStartNanos >= dwellNanos) {
            mineTicks = 0;
            state = State.MINING;
        }
    }

    private void tickMining(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (target == null || level.getBlockState(target).isAir() || !isMushroom(level.getBlockState(target))) {
            // Broken (or replaced) — on to the next mushroom.
            BooterClient.overlay("Mushroom mined");
            nextTarget(client);
            return;
        }
        aimAtTarget(player, level);
        boolean aimed = client.hitResult instanceof BlockHitResult bhr
                && client.hitResult.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(target);
        movement.setAttack(client, aimed);
        if (++mineTicks > MINE_TIMEOUT_TICKS) {
            BooterClient.chat("Mushroom Farmer: couldn't mine target — skipping.");
            skipTarget(client);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void beginLooking() {
        movement.releaseAll(Minecraft.getInstance());
        lookStartNanos = System.nanoTime();
        state = State.LOOKING;
    }

    private void aimAtTarget(LocalPlayer player, ClientLevel level) {
        // Aim at the centre of the block's actual hitbox, not the block centre.
        // A small mushroom's outline is short (top ~0.375), so aiming at y+0.5
        // points above it and the ray misses — break the block by aiming low.
        double lx = 0.5;
        double ly = 0.25;
        double lz = 0.5;
        VoxelShape shape = level.getBlockState(target).getShape(level, target);
        if (!shape.isEmpty()) {
            AABB b = shape.bounds();
            lx = (b.minX + b.maxX) / 2.0;
            ly = (b.minY + b.maxY) / 2.0;
            lz = (b.minZ + b.maxZ) / 2.0;
        }
        double cx = target.getX() + lx;
        double cy = target.getY() + ly;
        double cz = target.getZ() + lz;
        double dx = cx - player.getX();
        double dz = cz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dyEye = cy - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.001, horiz))), -90.0, 90.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
    }

    private boolean repath(Minecraft client, LocalPlayer player, ClientLevel level) {
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> p = finder.findPath(level, player.blockPosition(), target);
        if (p == null || p.size() < 2) {
            return false;
        }
        path = p;
        pathIndex = 1;
        stuckTicks = 0;
        lastDistSq = Double.MAX_VALUE;
        return true;
    }

    private void nextTarget(Minecraft client) {
        movement.setAttack(client, false);
        target = null;
        state = State.SEEKING;
    }

    private void skipTarget(Minecraft client) {
        movement.setAttack(client, false);
        if (target != null) {
            skip.add(target);
        }
        target = null;
        state = State.SEEKING;
    }

    private double distToTargetSq(LocalPlayer player) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void halt(Minecraft client) {
        movement.setAttack(client, false);
        movement.releaseAll(client);
        rotation.setActive(false);
        state = State.IDLE;
        target = null;
        path = Collections.emptyList();
    }

    private BlockPos findNearestMushroom(ClientLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (skip.contains(m)) {
                        continue;
                    }
                    if (isMushroom(level.getBlockState(m))) {
                        double d = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (d < bestSq) {
                            bestSq = d;
                            best = m.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isMushroom(BlockState state) {
        Block b = state.getBlock();
        // Only the small mushroom plants — never the huge mushroom blocks.
        return b == Blocks.RED_MUSHROOM || b == Blocks.BROWN_MUSHROOM;
    }
}
