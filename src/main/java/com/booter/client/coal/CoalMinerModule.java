package com.booter.client.coal;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coal miner: dynamically scans for connected coal-ore veins (even fully buried in
 * stone), optionally pathfinds toward the nearest accessible spot, then digs
 * straight through the stone to reach the vein and mines it out — repeating
 * indefinitely. Unlike the Block Miner (which only mines exposed blocks), this one
 * tunnels to encased ore.
 *
 * <p>Client-side only; vanilla movement / attack inputs and the shared smooth
 * {@link RotationManager}.
 */
public final class CoalMinerModule {
    public enum State {
        IDLE, SEEKING, WALKING, DIGGING, MINING
    }

    private static final int PATH_MAX_NODES = 8000;
    private static final int PATH_MAX_RADIUS = 96;
    private static final long BLACKLIST_MS = 30_000L;
    /** No progress toward the target for this many ticks → give up on it. */
    private static final int DIG_STUCK_TICKS = 200;
    /** Coal ores grouped into veins. */
    private static final Set<Block> COAL = Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower follower;

    private State state = State.IDLE;
    private final LinkedHashSet<BlockPos> vein = new LinkedHashSet<>();
    private BlockPos target;          // the coal block we're heading for
    private BlockPos mining;          // the block we're currently breaking
    private int mineTicks;
    private int digTicks;
    private double lastDigDistSq;
    private int scanTimer;
    private boolean announcedNone;
    private final Map<BlockPos, Long> blacklist = new HashMap<>();

    public CoalMinerModule(ConfigManager config, MovementController movement, RotationManager rotation) {
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

    public int getVeinSize() {
        return vein.size();
    }

    public List<BlockPos> getPath() {
        return follower.getPath();
    }

    public int getPathIndex() {
        return follower.getIndex();
    }

    // ---------------------------------------------------------------- lifecycle

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
        BooterClient.azaleaFarmer().stop(client);
        blacklist.clear();
        vein.clear();
        target = null;
        mining = null;
        scanTimer = 0;
        announcedNone = false;
        state = State.SEEKING;
        BooterClient.chat("Coal Miner started.");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        halt(client);
        BooterClient.chat("Coal Miner stopped.");
    }

    public void toggle(Minecraft client) {
        if (state == State.IDLE) {
            start(client);
        } else {
            stop(client);
        }
    }

    private void halt(Minecraft client) {
        movement.setAttack(client, false);
        movement.setCrouch(client, false);
        movement.releaseAll(client);
        rotation.setActive(false);
        follower.clear();
        state = State.IDLE;
        target = null;
        mining = null;
        vein.clear();
    }

    // ---------------------------------------------------------------- tick

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
        if (scanTimer > 0) {
            scanTimer--;
        }
        // Progress check: if the block we were breaking is gone, that's progress.
        if (mining != null && level.getBlockState(mining).isAir()) {
            mining = null;
            mineTicks = 0;
            digTicks = 0;
        }

        switch (state) {
            case SEEKING -> tickSeeking(client, player, level);
            case WALKING -> tickWalking(client, player, level);
            case DIGGING -> tickDigging(client, player, level);
            case MINING -> tickMining(client, player, level);
            default -> {
            }
        }
    }

    private void tickSeeking(Minecraft client, LocalPlayer player, ClientLevel level) {
        movement.setAttack(client, false);
        movement.setCrouch(client, false);
        if (scanTimer > 0) {
            return;
        }
        scanTimer = config.settings.minerScanInterval;

        List<BlockPos> best = findNearestVein(level, player);
        if (best == null) {
            if (!announcedNone) {
                BooterClient.chat("Coal Miner: no coal veins in range — scanning…");
                announcedNone = true;
            }
            return;
        }
        announcedNone = false;
        vein.clear();
        vein.addAll(best);
        target = nearestVeinBlock(player);
        if (target == null) {
            vein.clear();
            return;
        }
        BooterClient.chat("Coal Miner: vein of " + vein.size() + " at "
                + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
        // Try to walk to an accessible spot near it first; otherwise just dig there.
        List<BlockPos> path = approachPath(level, player, target);
        if (path != null && path.size() >= 2) {
            follower.setPath(path, player);
            state = State.WALKING;
        } else {
            beginDig(player);
        }
    }

    private void tickWalking(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!isCoal(level, target)) {
            nextTarget(client, player, level);
            return;
        }
        if (canMineCoal(level, player, target)) {
            movement.releaseAll(client);
            follower.clear();
            state = State.MINING;
            mineTicks = 0;
            return;
        }
        PathFollower.Status status = follower.tick(client, false, false);
        if (status == PathFollower.Status.ARRIVED || status == PathFollower.Status.STUCK
                || status == PathFollower.Status.IDLE) {
            // Reached the accessible spot (or can't get closer) — dig the rest.
            follower.clear();
            beginDig(player);
        }
    }

    /** Digs through stone straight toward the target coal block. */
    private void tickDigging(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!isCoal(level, target)) {
            nextTarget(client, player, level);
            return;
        }
        if (canMineCoal(level, player, target)) {
            movement.releaseAll(client);
            state = State.MINING;
            mineTicks = 0;
            return;
        }

        // No-progress watchdog (genuinely stuck, e.g. bedrock wall).
        double dsq = distSq(player, target);
        if (dsq < lastDigDistSq - 0.5) {
            lastDigDistSq = dsq;
            digTicks = 0;
        } else if (++digTicks > DIG_STUCK_TICKS) {
            BooterClient.chat("Coal Miner: can't reach vein — skipping.");
            blacklist.put(target, System.currentTimeMillis() + BLACKLIST_MS);
            nextTarget(client, player, level);
            return;
        }

        BlockPos block = nextDigBlock(level, player, target);
        if (block == null) {
            // Path ahead is clear — step toward the target.
            mining = null;
            walkToward(client, player, target);
            return;
        }
        // Break the obstructing block.
        if (!block.equals(mining)) {
            mining = block;
            mineTicks = 0;
        }
        mineAt(client, player, level, block);
        if (++mineTicks > config.settings.minerMineTimeout) {
            // Won't break (bedrock / obsidian) — blacklist it and try another route.
            blacklist.put(block, System.currentTimeMillis() + BLACKLIST_MS);
            mining = null;
            mineTicks = 0;
        }
    }

    private void tickMining(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!isCoal(level, target)) {
            BooterClient.overlay("Coal mined");
            nextTarget(client, player, level);
            return;
        }
        if (!canMineCoal(level, player, target)) {
            state = State.DIGGING; // lost line of sight / reach — dig back to it
            return;
        }
        mineAt(client, player, level, target);
        if (++mineTicks > config.settings.minerMineTimeout) {
            blacklist.put(target, System.currentTimeMillis() + BLACKLIST_MS);
            nextTarget(client, player, level);
        }
    }

    // ---------------------------------------------------------------- targeting

    private void beginDig(LocalPlayer player) {
        state = State.DIGGING;
        digTicks = 0;
        mining = null;
        mineTicks = 0;
        lastDigDistSq = target != null ? distSq(player, target) : Double.MAX_VALUE;
    }

    /** Picks the next coal block in the vein, or rescans if the vein is exhausted. */
    private void nextTarget(Minecraft client, LocalPlayer player, ClientLevel level) {
        movement.setAttack(client, false);
        mining = null;
        mineTicks = 0;
        vein.removeIf(b -> !isCoal(level, b) || blacklist.containsKey(b));
        target = nearestVeinBlock(player);
        if (target == null) {
            state = State.SEEKING;
            scanTimer = 0;
        } else {
            beginDig(player);
        }
    }

    private BlockPos nearestVeinBlock(LocalPlayer player) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos b : vein) {
            double d = distSq(player, b);
            if (d < bestSq) {
                bestSq = d;
                best = b;
            }
        }
        return best;
    }

    /** Flood-fills connected coal ore in the scan radius; returns the nearest vein's blocks. */
    private List<BlockPos> findNearestVein(ClientLevel level, LocalPlayer player) {
        long now = System.currentTimeMillis();
        blacklist.values().removeIf(expiry -> now >= expiry);

        int radius = config.settings.coalScanRadius;
        BlockPos center = player.blockPosition();
        Set<Long> visited = new HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        List<BlockPos> best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.getX() + dx;
                    int y = center.getY() + dy;
                    int z = center.getZ() + dz;
                    long key = BlockPos.asLong(x, y, z);
                    if (visited.contains(key)) {
                        continue;
                    }
                    m.set(x, y, z);
                    if (blacklist.containsKey(m) || !COAL.contains(level.getBlockState(m).getBlock())) {
                        visited.add(key);
                        continue;
                    }
                    List<BlockPos> blocks = floodFill(level, new BlockPos(x, y, z), visited, center, radius);
                    double near = Double.MAX_VALUE;
                    for (BlockPos b : blocks) {
                        near = Math.min(near, distSq(player, b));
                    }
                    if (near < bestSq) {
                        bestSq = near;
                        best = blocks;
                    }
                }
            }
        }
        return best;
    }

    private List<BlockPos> floodFill(ClientLevel level, BlockPos seed, Set<Long> visited, BlockPos center, int radius) {
        List<BlockPos> blocks = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed.asLong());
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            blocks.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int nx = p.getX() + dx;
                        int ny = p.getY() + dy;
                        int nz = p.getZ() + dz;
                        if (Math.abs(nx - center.getX()) > radius
                                || Math.abs(ny - center.getY()) > radius
                                || Math.abs(nz - center.getZ()) > radius) {
                            continue;
                        }
                        long key = BlockPos.asLong(nx, ny, nz);
                        if (visited.contains(key)) {
                            continue;
                        }
                        visited.add(key);
                        BlockPos np = new BlockPos(nx, ny, nz);
                        if (!blacklist.containsKey(np) && COAL.contains(level.getBlockState(np).getBlock())) {
                            queue.add(np);
                        }
                    }
                }
            }
        }
        return blocks;
    }

    /** A* path to the nearest standable spot near the target (open approach), or null. */
    private List<BlockPos> approachPath(ClientLevel level, LocalPlayer player, BlockPos coal) {
        int r = 5;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos pp = player.blockPosition();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos spot = coal.offset(dx, dy, dz);
                    if (!BaritonePathfinder.isStandable(level, spot)) {
                        continue;
                    }
                    double d = spot.distSqr(pp);
                    if (d < bestSq) {
                        bestSq = d;
                        best = spot;
                    }
                }
            }
        }
        if (best == null || best.distSqr(pp) <= 4) {
            return null; // no open spot, or we're already there → just dig
        }
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        return finder.findPath(level, pp, best);
    }

    // ---------------------------------------------------------------- digging helpers

    /**
     * The next block to break to make progress toward {@code coal}: the 2-tall cell
     * directly ahead (toward the coal, descending if the coal is lower) while still
     * approaching, or the block on the direct sightline to the coal once at its
     * column (so it digs up/down to reach it). Null if nothing's in the way.
     */
    private BlockPos nextDigBlock(ClientLevel level, LocalPlayer player, BlockPos coal) {
        double dx = (coal.getX() + 0.5) - player.getX();
        double dz = (coal.getZ() + 0.5) - player.getZ();
        int feetY = Mth.floor(player.getY());
        if (dx * dx + dz * dz > 1.6) {
            int ox;
            int oz;
            if (Math.abs(dx) >= Math.abs(dz)) {
                ox = dx >= 0 ? 1 : -1;
                oz = 0;
            } else {
                ox = 0;
                oz = dz >= 0 ? 1 : -1;
            }
            int ax = Mth.floor(player.getX()) + ox;
            int az = Mth.floor(player.getZ()) + oz;
            BlockPos feet = solidAt(level, ax, feetY, az);
            if (feet != null) {
                return feet;
            }
            BlockPos head = solidAt(level, ax, feetY + 1, az);
            if (head != null) {
                return head;
            }
            if (coal.getY() < feetY) {
                return solidAt(level, ax, feetY - 1, az); // open the floor to descend
            }
            return null; // ahead clear → walk forward
        }
        // At the coal's column — mine straight toward it (handles up/down via reach).
        return firstObstruction(level, player, coal);
    }

    /** Mineable solid (non-air, non-fluid, collidable, not blacklisted) block, or null. */
    private BlockPos solidAt(ClientLevel level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (blacklist.containsKey(p)) {
            return null;
        }
        BlockState st = level.getBlockState(p);
        if (st.isAir() || !st.getFluidState().isEmpty() || st.getCollisionShape(level, p).isEmpty()) {
            return null;
        }
        return p;
    }

    /** First solid block on the eye→coal sightline that isn't the coal, or null. */
    private BlockPos firstObstruction(ClientLevel level, LocalPlayer player, BlockPos coal) {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = new Vec3(coal.getX() + 0.5, coal.getY() + 0.5, coal.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(coal) && !blacklist.containsKey(hit.getBlockPos())) {
            return hit.getBlockPos();
        }
        return null;
    }

    /** True if the coal is within reach and the sightline to its centre is clear. */
    private boolean canMineCoal(ClientLevel level, LocalPlayer player, BlockPos coal) {
        if (distSq(player, coal) > range() * range()) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aim = new Vec3(coal.getX() + 0.5, coal.getY() + 0.5, coal.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(coal);
    }

    /** Aims at a block centre and holds attack when the crosshair is on it. */
    private void mineAt(Minecraft client, LocalPlayer player, ClientLevel level, BlockPos pos) {
        movement.releaseAll(client);
        movement.setCrouch(client, config.settings.minerCrouch);
        selectMiningSlot(client, player); // hold the mining tool (slot 2 by default)
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        double dx = cx - player.getX();
        double dz = cz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dyEye = cy - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.001, horiz))), -90.0, 90.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
        boolean aimed = client.hitResult instanceof BlockHitResult bhr
                && client.hitResult.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(pos);
        movement.setAttack(client, aimed);
    }

    /** Selects the configured mining-tool hotbar slot (and tells the server), so the
     *  right tool is held while breaking glass panes / stone / coal in the way. */
    private void selectMiningSlot(Minecraft client, LocalPlayer player) {
        int idx = Mth.clamp(config.settings.coalMineSlot - 1, 0, 8);
        if (player.getInventory().getSelectedSlot() == idx) {
            return;
        }
        player.getInventory().setSelectedSlot(idx);
        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundSetCarriedItemPacket(idx));
        }
    }

    /** Faces and walks toward a block (forward + auto-jump). */
    private void walkToward(Minecraft client, LocalPlayer player, BlockPos pos) {
        movement.setAttack(client, false);
        movement.setCrouch(client, false);
        double dx = (pos.getX() + 0.5) - player.getX();
        double dz = (pos.getZ() + 0.5) - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dyEye = (pos.getY() + 0.5) - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.5, horiz))), -25.0, 25.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
        movement.tick(client, false, false, false, true);
    }

    private boolean isCoal(ClientLevel level, BlockPos pos) {
        return pos != null && COAL.contains(level.getBlockState(pos).getBlock());
    }

    private double range() {
        return config.settings.minerRange;
    }

    private static double distSq(LocalPlayer player, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - player.getX();
        double dy = (pos.getY() + 0.5) - player.getY();
        double dz = (pos.getZ() + 0.5) - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
