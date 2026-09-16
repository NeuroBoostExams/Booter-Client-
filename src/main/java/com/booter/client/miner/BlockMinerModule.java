package com.booter.client.miner;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.rotation.RotationManager;
import com.booter.client.waypoint.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A configurable, scoring-based block miner. The player chooses which vanilla
 * block types to target; the module continuously scans the loaded world (at a
 * configurable interval), scores every candidate by value, distance and
 * estimated mining cost, picks the highest-scoring <em>reachable</em> target
 * (verified with A* so blocks behind walls are skipped), pathfinds to it,
 * smoothly looks at it and mines it (crouching) by holding left-click. It
 * retargets when a block breaks or becomes unreachable, and only switches to a
 * better target when the improvement clears a threshold (and a switch delay),
 * to avoid erratic flip-flopping.
 *
 * <p>Targeting is a weighted priority score summed from practical factors:
 * <pre>
 *   score = distanceWeight     // closer is better
 *         + visibilityWeight   // a clear line of sight is better
 *         + rotationWeight     // a smaller turn from where we're looking is better
 *         + clusterWeight      // blocks surrounded by more targets are better
 *         + reachabilityWeight // must be realistically mineable (in reach / pathable)
 *         + efficiencyWeight   // softer (faster-to-break) blocks are better
 *         - heightPenalty      // big vertical offset is worse
 *         - cooldownPenalty    // recently mined / abandoned spots are deferred
 * </pre>
 * All whitelisted block types are treated as equally wanted; selection is purely
 * by these practical factors.
 *
 * <p>Reads only the client's own world (no server cues) and uses only vanilla
 * movement/attack/sneak inputs.
 */
public final class BlockMinerModule {
    public enum State {
        IDLE, TRAVELING, SEEKING, WALKING, MINING, TUNNEL
    }

    private static final double NODE_REACH = 0.65;
    private static final int PATH_MAX_NODES = 8000;
    private static final int PATH_MAX_RADIUS = 96;
    private static final int SCAN_VERTICAL = 24;
    private static final int STUCK_TICKS = 60;
    /** Max A* reachability checks per scan (keeps scans cheap). */
    private static final int MAX_PATH_ATTEMPTS = 16;
    /** How long an obstructed / unreachable block stays blacklisted (ms). */
    private static final long BLACKLIST_MS = 30_000L;
    /** How close to a waypoint counts as "arrived" to start mining there. */
    private static final double WAYPOINT_ARRIVE = 3.0;

    // ---- Weighted targeting factors (additive priority score) ----
    /** Max bonus for a block right next to us, fading to 0 at the scan edge. */
    private static final double W_DISTANCE = 40.0;
    /** Bonus for having a clear line of sight to the block. */
    private static final double W_VISIBILITY = 25.0;
    /** Max bonus for a block close to where we're already looking. */
    private static final double W_ROTATION = 15.0;
    /** Bonus per neighbouring target block (clusters preferred). */
    private static final double W_CLUSTER = 6.0;
    private static final int CLUSTER_CAP = 8;
    /** Reachability bonus: already in reach vs. reachable only by pathing to a spot. */
    private static final double W_REACH_INREACH = 35.0;
    private static final double W_REACH_PATH = 18.0;
    /** Max bonus for low-hardness (fast to mine) blocks. */
    private static final double W_EFFICIENCY = 12.0;
    /** Penalty per block of vertical offset from the player. */
    private static final double HEIGHT_PENALTY = 3.0;
    /** Penalty applied to recently mined / just-abandoned spots (anti-oscillation). */
    private static final double COOLDOWN_PENALTY = 60.0;
    private static final long COOLDOWN_MS = 4_000L;
    /** Cap on candidates fully evaluated (line of sight + A*) per scan. */
    private static final int EVAL_CAP = 48;

    // ---- Dynamic vein mining ----
    /** Per-block weight added to a vein's value for each block it contains. */
    private static final double VEIN_SIZE_BONUS = 1.0;
    /** Distance softening in the vein score (closer veins preferred, not absolutely). */
    private static final double VEIN_DIST_BIAS = 6.0;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;

    private State state = State.IDLE;
    private BlockPos target;
    private double currentScore;
    private List<BlockPos> path = Collections.emptyList();
    private int pathIndex;
    private int stuckTicks;
    private int mineTicks;
    private int scanTimer;
    private int sinceSwitch;
    private double lastDistSq;
    private int lastCandidateCount;
    private boolean announcedNoTargets;
    private boolean routeActive;
    private int routeIndex;
    /** Temporarily blacklisted blocks (obstructed/unreachable) → expiry time millis. */
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    /** Recently mined / abandoned blocks on a short cooldown → expiry millis (anti-oscillation). */
    private final Map<BlockPos, Long> cooldown = new HashMap<>();
    /** The connected ore vein we've committed to mining (dynamic mode); empty otherwise. */
    private final LinkedHashSet<BlockPos> currentVein = new LinkedHashSet<>();
    /** Ordered floor cells of the strip-mine tunnel pattern (tunnel mode). */
    private List<BlockPos> tunnelCells = Collections.emptyList();
    private int patternIndex;
    private int cellTicks;
    private BlockPos oreTarget;
    private int oreTicks;

    /** All glass blocks AND panes (clear, tinted, every stained colour) — one toggle. */
    private static final Set<Block> GLASS = Set.of(
            Blocks.GLASS, Blocks.TINTED_GLASS, Blocks.GLASS_PANE,
            Blocks.WHITE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
            Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS,
            Blocks.PINK_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS,
            Blocks.CYAN_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS,
            Blocks.BROWN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.RED_STAINED_GLASS,
            Blocks.BLACK_STAINED_GLASS,
            Blocks.WHITE_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE, Blocks.MAGENTA_STAINED_GLASS_PANE,
            Blocks.LIGHT_BLUE_STAINED_GLASS_PANE, Blocks.YELLOW_STAINED_GLASS_PANE, Blocks.LIME_STAINED_GLASS_PANE,
            Blocks.PINK_STAINED_GLASS_PANE, Blocks.GRAY_STAINED_GLASS_PANE, Blocks.LIGHT_GRAY_STAINED_GLASS_PANE,
            Blocks.CYAN_STAINED_GLASS_PANE, Blocks.PURPLE_STAINED_GLASS_PANE, Blocks.BLUE_STAINED_GLASS_PANE,
            Blocks.BROWN_STAINED_GLASS_PANE, Blocks.GREEN_STAINED_GLASS_PANE, Blocks.RED_STAINED_GLASS_PANE,
            Blocks.BLACK_STAINED_GLASS_PANE);

    /** Valuable ores the tunnel miner grabs when it exposes them. */
    private static final Set<Block> ORES = Set.of(
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);
    /** Aim offset (fraction of block extent, [-0.3, 0.3]) within the central 60%. */
    private final java.util.Random aimRandom = new java.util.Random();
    private double aimFracX;
    private double aimFracY;
    private double aimFracZ;

    public BlockMinerModule(ConfigManager config, MovementController movement, RotationManager rotation) {
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

    public double getCurrentScore() {
        return currentScore;
    }

    public int getCandidateCount() {
        return lastCandidateCount;
    }

    /** Blocks remaining in the vein currently being mined (dynamic mode). */
    public int getVeinSize() {
        return currentVein.size();
    }

    /** Tunnel-pattern progress as "done/total" cells, or null when not in tunnel mode. */
    public String getTunnelProgress() {
        if (tunnelCells.isEmpty()) {
            return null;
        }
        return Math.min(patternIndex, tunnelCells.size()) + "/" + tunnelCells.size();
    }

    public boolean isRouteActive() {
        return routeActive;
    }

    public int getRouteIndex() {
        return routeIndex;
    }

    // ---------------------------------------------------------------- lifecycle

    public void start(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.autoFisher().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);
        blacklist.clear();
        cooldown.clear();
        currentVein.clear();
        announcedNoTargets = false;
        target = null;
        currentScore = 0;
        scanTimer = 0;
        sinceSwitch = 0;
        routeActive = false;
        tunnelCells = Collections.emptyList();
        patternIndex = 0;
        cellTicks = 0;

        // Tunnel (strip-mine) mode: dig straight parallel tunnels, leaving a 1-block
        // wall between them. Digs the whole cross-section regardless of the whitelist.
        if (config.settings.minerTunnelMode) {
            buildTunnel(player);
            if (tunnelCells.isEmpty()) {
                BooterClient.chat("Block Miner: tunnel pattern is empty.");
                return;
            }
            state = State.TUNNEL;
            BooterClient.chat("Block Miner: tunnel mode — " + config.settings.minerTunnelCount
                    + " tunnels x " + config.settings.minerTunnelLength + " (" + tunnelCells.size() + " cells).");
            return;
        }

        if (!anyEnabled()) {
            BooterClient.chat("Block Miner: enable at least one block type first.");
            return;
        }
        routeActive = config.settings.minerUseRoute && BooterClient.waypoints().size() > 0;
        if (config.settings.minerUseRoute && !routeActive) {
            BooterClient.chat("Block Miner: route mode is on but no waypoints are set.");
            return;
        }
        if (routeActive) {
            routeIndex = 0;
            beginTravel(client);
            BooterClient.chat("Block Miner started — route mode (" + BooterClient.waypoints().size() + " waypoints).");
        } else {
            state = State.SEEKING;
            BooterClient.chat("Block Miner started.");
        }
    }

    /**
     * Builds the ordered floor cells of a serpentine strip-mine: {@code count}
     * straight tunnels of {@code length} along the player's facing, each offset two
     * blocks sideways from the last (so a 1-block wall is left between tunnels) and
     * joined by a short connector at alternating ends. All at the start Y.
     */
    private void buildTunnel(LocalPlayer player) {
        List<BlockPos> cells = new ArrayList<>();
        net.minecraft.core.Direction forward = player.getDirection();
        net.minecraft.core.Direction right = forward.getClockWise();
        int dirX = forward.getStepX();
        int dirZ = forward.getStepZ();
        int rX = right.getStepX();
        int rZ = right.getStepZ();
        BlockPos origin = player.blockPosition();
        int curX = origin.getX();
        int oy = origin.getY();
        int curZ = origin.getZ();
        int length = config.settings.minerTunnelLength;
        int count = config.settings.minerTunnelCount;
        for (int t = 0; t < count; t++) {
            for (int i = 0; i < length; i++) {
                curX += dirX;
                curZ += dirZ;
                cells.add(new BlockPos(curX, oy, curZ));
            }
            if (t < count - 1) {
                // Connector: step two over (clears the wall cell + reaches the next tunnel).
                for (int j = 0; j < 2; j++) {
                    curX += rX;
                    curZ += rZ;
                    cells.add(new BlockPos(curX, oy, curZ));
                }
                dirX = -dirX; // serpentine: reverse direction for the next tunnel
                dirZ = -dirZ;
            }
        }
        tunnelCells = cells;
        patternIndex = 0;
        cellTicks = 0;
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        halt(client);
        BooterClient.chat("Block Miner stopped.");
    }

    public void toggle(Minecraft client) {
        if (state == State.IDLE) {
            start(client);
        } else {
            stop(client);
        }
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
        sinceSwitch++;
        if (scanTimer > 0) {
            scanTimer--;
        }

        switch (state) {
            case TRAVELING -> tickTravel(client, player, level);
            case SEEKING -> tickSeeking(client, player, level);
            case WALKING -> tickWalking(client, player, level);
            case MINING -> tickMining(client, player, level);
            case TUNNEL -> tickTunnel(client, player, level);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- route

    /** The block at the current waypoint, or null if the route is empty/invalid. */
    private BlockPos waypointBlock() {
        Waypoint w = currentWaypoint();
        return w == null ? null : BlockPos.containing(w.x, w.y, w.z);
    }

    private Waypoint currentWaypoint() {
        var wps = BooterClient.waypoints().view();
        if (wps.isEmpty()) {
            return null;
        }
        return wps.get(Math.floorMod(routeIndex, wps.size()));
    }

    private boolean currentWaypointMines() {
        Waypoint wp = currentWaypoint();
        return wp == null || wp.isArrived();
    }

    /** Where to scan for blocks: around the current waypoint in route mode, else around the player. */
    private BlockPos scanCenter(LocalPlayer player) {
        if (routeActive) {
            BlockPos wp = waypointBlock();
            if (wp != null) {
                return wp;
            }
        }
        return player.blockPosition();
    }

    /** Starts pathfinding to the current waypoint (route mode). */
    private void beginTravel(Minecraft client) {
        BlockPos wp = waypointBlock();
        if (wp == null) {
            halt(client);
            return;
        }
        LocalPlayer player = client.player;
        double dx = (wp.getX() + 0.5) - player.getX();
        double dy = (wp.getY() + 0.5) - player.getY();
        double dz = (wp.getZ() + 0.5) - player.getZ();
        if (dx * dx + dy * dy + dz * dz <= WAYPOINT_ARRIVE * WAYPOINT_ARRIVE) {
            if (!currentWaypointMines()) {
                advanceRoute(client);
                return;
            }
            // Already at an arrived waypoint — start mining around it.
            state = State.SEEKING;
            scanTimer = 0;
            return;
        }
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> p = finder.findPath(client.level, player.blockPosition(), wp);
        if (p == null || p.size() < 2) {
            // Can't path there — mine whatever is reachable near it instead.
            state = State.SEEKING;
            scanTimer = 0;
            return;
        }
        path = p;
        pathIndex = 1;
        stuckTicks = 0;
        lastDistSq = Double.MAX_VALUE;
        state = State.TRAVELING;
    }

    private void advanceRoute(Minecraft client) {
        int size = BooterClient.waypoints().size();
        if (size == 0) {
            halt(client);
            return;
        }
        routeIndex = (routeIndex + 1) % size; // loop
        beginTravel(client);
    }

    private void tickTravel(Minecraft client, LocalPlayer player, ClientLevel level) {
        movement.setAttack(client, false);
        movement.setCrouch(client, false);
        BlockPos wp = waypointBlock();
        if (wp == null) {
            halt(client);
            return;
        }
        double dx = (wp.getX() + 0.5) - player.getX();
        double dy = (wp.getY() + 0.5) - player.getY();
        double dz = (wp.getZ() + 0.5) - player.getZ();
        if (dx * dx + dy * dy + dz * dz <= WAYPOINT_ARRIVE * WAYPOINT_ARRIVE) {
            movement.releaseAll(client);
            if (!currentWaypointMines()) {
                advanceRoute(client);
                return;
            }
            state = State.SEEKING;
            scanTimer = 0;
            return;
        }
        if (pathIndex >= path.size()) {
            if (!currentWaypointMines()) {
                advanceRoute(client);
                return;
            }
            // Reached path end near an arrived waypoint — mine nearby anyway.
            movement.releaseAll(client);
            state = State.SEEKING;
            scanTimer = 0;
            return;
        }

        BlockPos node = path.get(pathIndex);
        double ndx = (node.getX() + 0.5) - player.getX();
        double ndz = (node.getZ() + 0.5) - player.getZ();
        double horizSq = ndx * ndx + ndz * ndz;
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
            // Stuck on the way to the waypoint — try again, or give up on this one.
            Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
            List<BlockPos> p = finder.findPath(level, player.blockPosition(), wp);
            if (p != null && p.size() >= 2) {
                path = p;
                pathIndex = 1;
                stuckTicks = 0;
                lastDistSq = Double.MAX_VALUE;
            } else {
                advanceRoute(client);
            }
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-ndx, ndz));
        double horiz = Math.sqrt(horizSq);
        double dyEye = (node.getY() + 0.6) - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.1, horiz))), 0.0, 10.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
        movement.tick(client, false, false, false, true);
    }

    private void tickSeeking(Minecraft client, LocalPlayer player, ClientLevel level) {
        movement.setAttack(client, false);
        if (!anyEnabled()) {
            BooterClient.chat("Block Miner: no block types enabled.");
            halt(client);
            return;
        }
        // Scan only on the interval; leave crouch held while waiting so quick
        // consecutive mines don't flicker the sneak key.
        if (scanTimer > 0) {
            return;
        }
        scanTimer = config.settings.minerScanInterval;

        if (routeActive) {
            Target best = findBest(level, player, scanCenter(player));
            if (best == null) {
                // Everything mined around this waypoint — move on to the next (loops).
                movement.setCrouch(client, false);
                advanceRoute(client);
                return;
            }
            announcedNoTargets = false;
            adopt(best);
            movement.setCrouch(client, state == State.MINING && config.settings.minerCrouch);
            sinceSwitch = 0;
            return;
        }

        // ---- Dynamic vein mining: commit to a vein and mine it out, then find the next. ----
        pruneVein(level);
        if (currentVein.isEmpty()) {
            Vein vein = findBestVein(level, player);
            if (vein == null) {
                movement.setCrouch(client, false);
                if (!announcedNoTargets) {
                    BooterClient.chat("Block Miner: no veins in range — scanning…");
                    announcedNoTargets = true;
                }
                return;
            }
            currentVein.addAll(vein.blocks);
            BooterClient.chat(String.format("Block Miner: vein of %d (value %.0f) at %d %d %d.",
                    vein.blocks.size(), vein.value, vein.center.getX(), vein.center.getY(), vein.center.getZ()));
        }
        Target best = findBest(level, player, scanCenter(player), currentVein);
        if (best == null) {
            // No reachable/exposed block left in this vein right now — drop it and look elsewhere.
            abandonVein();
            return;
        }
        announcedNoTargets = false;
        adopt(best);
        movement.setCrouch(client, state == State.MINING && config.settings.minerCrouch);
        sinceSwitch = 0;
    }

    private void tickWalking(Minecraft client, LocalPlayer player, ClientLevel level) {
        movement.setCrouch(client, false); // uncrouch while pathfinding to the next block
        if (target == null || !matches(level.getBlockState(target))) {
            nextTarget(client);
            return;
        }
        // Reached a position where we can actually mine the block (in reach + view).
        if (distToTargetSq(player) <= range() * range() && hasLineOfSight(level, player, target)) {
            movement.releaseAll(client);
            mineTicks = 0;
            state = State.MINING;
            return;
        }
        monitorForBetter(level, player);
        if (state != State.WALKING) {
            return; // switched target
        }
        if (pathIndex >= path.size()) {
            // Arrived at the strafe spot but still can't see/reach it — give up on this one.
            skipTarget(client);
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

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.sqrt(horizSq);
        double dyEye = (node.getY() + 0.6) - player.getEyeY();
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.1, horiz))), 0.0, 10.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
        movement.tick(client, false, false, false, true);
    }

    private void tickMining(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (target == null) {
            nextTarget(client);
            return;
        }
        BlockState st = level.getBlockState(target);
        if (st.isAir() || !matches(st)) {
            BooterClient.overlay("Block mined");
            // Brief cooldown on the spot so we don't re-fixate on the same area.
            cooldown.put(target.immutable(), System.currentTimeMillis() + COOLDOWN_MS);
            currentVein.remove(target); // one fewer block in the committed vein
            nextTarget(client);
            return;
        }
        monitorForBetter(level, player);
        if (state != State.MINING) {
            return; // switched target
        }
        movement.setCrouch(client, config.settings.minerCrouch);

        // If we can't actually see a face of the block from here (covered by
        // grass, buried, blocked by another block), don't waste time — blacklist
        // it for 30s and move on immediately.
        if (!hasLineOfSight(level, player, target)) {
            BooterClient.chat("Block Miner: target obstructed — skipping.");
            skipTarget(client);
            return;
        }

        aimAtTarget(player, level);
        boolean aimed = client.hitResult instanceof BlockHitResult bhr
                && client.hitResult.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(target);
        movement.setAttack(client, aimed);
        if (++mineTicks > config.settings.minerMineTimeout) {
            BooterClient.chat("Block Miner: couldn't mine target — skipping.");
            skipTarget(client);
        }
    }

    // ---------------------------------------------------------------- tunnel pattern

    /**
     * Walks the serpentine strip-mine: for each floor cell in order, clears the
     * tunnel cross-section ahead (any solid block, regardless of the whitelist),
     * then steps into the cleared cell. A 1-block wall is left between parallel
     * tunnels (only the connector rows breach it). Stops when the pattern is done.
     */
    private void tickTunnel(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (patternIndex >= tunnelCells.size()) {
            BooterClient.chat("Block Miner: tunnel pattern complete.");
            halt(client);
            return;
        }
        // Per-cell watchdog: skip a cell we can't clear/reach (bedrock, snag, …).
        if (++cellTicks > config.settings.minerMineTimeout + 100) {
            BooterClient.chat("Block Miner: tunnel cell stuck — skipping.");
            advanceCell();
            return;
        }

        // Opportunistic ore mining: grab any valuable ore the tunnel has exposed
        // (e.g. diamonds in the walls/floor) before moving on. No detours — only
        // ores already in reach with a clear view from where we stand.
        if (config.settings.minerTunnelMineOres) {
            BlockPos ore = nearestReachableOre(level, player);
            if (ore != null) {
                if (!ore.equals(oreTarget)) {
                    oreTarget = ore;
                    oreTicks = 0;
                }
                cellTicks = 0; // making progress (mining ore) — don't trip the watchdog
                target = ore;
                movement.releaseAll(client);
                movement.setCrouch(client, config.settings.minerCrouch);
                aimAtTarget(player, level);
                boolean aimed = client.hitResult instanceof BlockHitResult bhr
                        && client.hitResult.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(ore);
                movement.setAttack(client, aimed);
                if (++oreTicks > config.settings.minerMineTimeout) {
                    blacklist.put(ore, System.currentTimeMillis() + BLACKLIST_MS);
                    oreTarget = null;
                    oreTicks = 0;
                }
                return;
            }
            oreTarget = null;
        }

        BlockPos cell = tunnelCells.get(patternIndex);
        BlockPos block = firstBlockToClear(level, cell);
        if (block != null) {
            target = block;
            double range = range();
            if (distToTargetSq(player) <= range * range && hasLineOfSight(level, player, block)) {
                // In reach: stand still and mine it.
                movement.releaseAll(client);
                movement.setCrouch(client, config.settings.minerCrouch);
                aimAtTarget(player, level);
                boolean aimed = client.hitResult instanceof BlockHitResult bhr
                        && client.hitResult.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(block);
                movement.setAttack(client, aimed);
            } else {
                // Not in reach yet — walk up to the cell.
                movement.setAttack(client, false);
                movement.setCrouch(client, false);
                walkTowardCell(client, player, cell);
            }
            return;
        }

        // Cross-section clear — step into the cell, then move on to the next.
        target = null;
        movement.setAttack(client, false);
        movement.setCrouch(client, false);
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        if (dx * dx + dz * dz <= 0.5 * 0.5 && Math.abs(cell.getY() - player.getY()) <= 1.25) {
            advanceCell();
            return;
        }
        walkTowardCell(client, player, cell);
    }

    private void advanceCell() {
        patternIndex++;
        cellTicks = 0;
        mineTicks = 0;
    }

    /** Nearest exposed, in-reach ore around the player (for opportunistic tunnel mining). */
    private BlockPos nearestReachableOre(ClientLevel level, LocalPlayer player) {
        double range = range();
        double rangeSq = range * range;
        int r = (int) Math.ceil(range) + 1;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (!ORES.contains(level.getBlockState(m).getBlock()) || blacklist.containsKey(m)) {
                        continue;
                    }
                    double ddx = (m.getX() + 0.5) - px;
                    double ddy = (m.getY() + 0.5) - py;
                    double ddz = (m.getZ() + 0.5) - pz;
                    double dsq = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (dsq > rangeSq || dsq >= bestSq) {
                        continue;
                    }
                    if (isExposed(level, m) && hasLineOfSight(level, player, m)) {
                        bestSq = dsq;
                        best = m.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** First solid (non-air, non-fluid, collidable) block in the cell's cross-section, or null. */
    private BlockPos firstBlockToClear(ClientLevel level, BlockPos cell) {
        int height = config.settings.minerTunnelHeight;
        for (int i = 0; i < height; i++) {
            BlockPos p = cell.above(i);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || !st.getFluidState().isEmpty()) {
                continue;
            }
            if (st.getCollisionShape(level, p).isEmpty()) {
                continue; // plants/torches etc. — walkable, no need to break
            }
            return p;
        }
        return null;
    }

    /** Faces and walks toward a tunnel cell (forward + auto-jump). */
    private void walkTowardCell(Minecraft client, LocalPlayer player, BlockPos cell) {
        double dx = (cell.getX() + 0.5) - player.getX();
        double dz = (cell.getZ() + 0.5) - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        rotation.setTargetRotation(yaw, 0.0f);
        rotation.setActive(true);
        movement.tick(client, false, false, false, true);
    }

    // ---------------------------------------------------------------- targeting

    /** Periodically re-scans and switches to a better target if it clears the threshold + delay. */
    private void monitorForBetter(ClientLevel level, LocalPlayer player) {
        if (scanTimer > 0) {
            return;
        }
        scanTimer = config.settings.minerScanInterval;
        // In dynamic mode only switch to better blocks WITHIN the committed vein, so
        // we finish a vein instead of flip-flopping; route mode re-scans globally.
        Target best = routeActive
                ? findBest(level, player, scanCenter(player))
                : findBest(level, player, scanCenter(player), currentVein);
        if (best == null || best.pos.equals(target)) {
            return;
        }
        if (sinceSwitch < config.settings.minerSwitchDelay) {
            return;
        }
        double threshold = config.settings.minerSwitchThreshold;
        if (best.score > currentScore * (1.0 + threshold)) {
            adopt(best);
            sinceSwitch = 0;
        }
    }

    /** Adopts the given target and sets state to MINING (in reach) or WALKING. */
    private void adopt(Target t) {
        // Put the target we're leaving on a short cooldown so we don't immediately
        // flip back to it (anti-oscillation).
        if (target != null && !target.equals(t.pos)) {
            cooldown.put(target, System.currentTimeMillis() + COOLDOWN_MS);
        }
        target = t.pos;
        currentScore = t.score;
        mineTicks = 0;
        stuckTicks = 0;
        lastDistSq = Double.MAX_VALUE;
        // Roll a fresh aim point in the central 60% of the block ([-0.3, 0.3] of the
        // extent from center) — varies per block, never the edges.
        aimFracX = (aimRandom.nextDouble() - 0.5) * 0.6;
        aimFracY = (aimRandom.nextDouble() - 0.5) * 0.6;
        aimFracZ = (aimRandom.nextDouble() - 0.5) * 0.6;
        if (t.inReach) {
            path = Collections.emptyList();
            pathIndex = 0;
            state = State.MINING;
        } else {
            path = t.path;
            pathIndex = 1;
            state = State.WALKING;
        }
    }

    /** Highest-scoring reachable matching block in the scan radius, or null. */
    private Target findBest(ClientLevel level, LocalPlayer player, BlockPos center) {
        return findBest(level, player, center, null);
    }

    /**
     * Scores matching blocks and returns the highest-scoring reachable one
     * (A*-verified), or null. When {@code restrict} is non-null only those blocks
     * are considered (used to mine a committed vein); otherwise the whole scan
     * radius around {@code center} is searched.
     *
     * <p>Each candidate gets a cheap preliminary score (distance, rotation, cluster,
     * efficiency, height, cooldown); the top {@link #EVAL_CAP} are finalized with the
     * expensive factors (visibility raycast, reachability A*).
     */
    private Target findBest(ClientLevel level, LocalPlayer player, BlockPos center, Collection<BlockPos> restrict) {
        int radius = config.settings.minerScanRadius;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        float playerYaw = player.getYRot();
        float playerPitch = player.getXRot();
        double maxDist = Math.max(1.0, radius);

        long now = System.currentTimeMillis();
        blacklist.values().removeIf(expiry -> now >= expiry);
        cooldown.values().removeIf(expiry -> now >= expiry);

        List<Scored> cands = new ArrayList<>();
        if (restrict != null) {
            for (BlockPos p : restrict) {
                if (blacklist.containsKey(p)) {
                    continue;
                }
                BlockState st = level.getBlockState(p);
                if (!matches(st) || !isExposed(level, p)) {
                    continue;
                }
                cands.add(scoreCandidate(level, p, st, px, py, pz, playerYaw, playerPitch, maxDist));
            }
        } else {
            // Scan a tall column so blocks on different Y levels are found, not just feet level.
            int vertical = Math.min(radius, SCAN_VERTICAL);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -vertical; dy <= vertical; dy++) {
                        m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (blacklist.containsKey(m)) {
                            continue;
                        }
                        BlockState st = level.getBlockState(m);
                        if (!matches(st) || !isExposed(level, m)) {
                            continue;
                        }
                        cands.add(scoreCandidate(level, m.immutable(), st, px, py, pz, playerYaw, playerPitch, maxDist));
                    }
                }
            }
        }
        lastCandidateCount = cands.size();
        if (cands.isEmpty()) {
            return null;
        }
        cands.sort((a, b) -> Double.compare(b.score, a.score));
        return evaluateBest(level, player, cands);
    }

    /** Cheap preliminary per-block score (no raycast / A*). */
    private Scored scoreCandidate(ClientLevel level, BlockPos pos, BlockState st,
                                  double px, double py, double pz,
                                  float playerYaw, float playerPitch, double maxDist) {
        double ddx = (pos.getX() + 0.5) - px;
        double ddy = (pos.getY() + 0.5) - py;
        double ddz = (pos.getZ() + 0.5) - pz;
        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        double distW = W_DISTANCE * (1.0 - Mth.clamp(dist / maxDist, 0.0, 1.0));
        double horiz = Math.sqrt(ddx * ddx + ddz * ddz);
        float tYaw = (float) Math.toDegrees(Math.atan2(-ddx, ddz));
        float tPitch = (float) Math.toDegrees(-Math.atan2(ddy, Math.max(0.001, horiz)));
        double rotCost = Math.abs(Mth.degreesDifference(playerYaw, tYaw)) + Math.abs(tPitch - playerPitch);
        double rotW = W_ROTATION * (1.0 - Mth.clamp(rotCost / 270.0, 0.0, 1.0));
        double clusterW = W_CLUSTER * Math.min(countCluster(level, pos), CLUSTER_CAP);
        double hardness = Math.max(0.0, st.getDestroySpeed(level, pos));
        double effW = W_EFFICIENCY / (1.0 + hardness);
        double heightPen = HEIGHT_PENALTY * Math.abs(ddy);
        double cooldownPen = cooldown.containsKey(pos) ? COOLDOWN_PENALTY : 0.0;
        return new Scored(pos, distW + rotW + clusterW + effW - heightPen - cooldownPen, dist);
    }

    /** Finalizes the top sorted candidates with visibility + reachability, returns the best. */
    private Target evaluateBest(ClientLevel level, LocalPlayer player, List<Scored> cands) {
        double range = range();
        Target best = null;
        double bestScore = -Double.MAX_VALUE;
        int attempts = 0;
        int evaluated = 0;
        for (Scored c : cands) {
            if (evaluated >= EVAL_CAP) {
                break;
            }
            evaluated++;
            boolean los = hasLineOfSight(level, player, c.pos);

            if (c.dist <= range && los) {
                double s = c.score + W_VISIBILITY + W_REACH_INREACH;
                if (s > bestScore) {
                    bestScore = s;
                    best = new Target(c.pos, Collections.emptyList(), s, true);
                }
                continue;
            }
            if (attempts >= MAX_PATH_ATTEMPTS) {
                continue;
            }
            attempts++;
            BlockPos spot = findMiningSpot(level, player, c.pos);
            if (spot == null) {
                continue;
            }
            double visW = los ? W_VISIBILITY : 0.0;
            if (spot.equals(player.blockPosition())) {
                double s = c.score + visW + W_REACH_INREACH;
                if (s > bestScore) {
                    bestScore = s;
                    best = new Target(c.pos, Collections.emptyList(), s, true);
                }
                continue;
            }
            Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
            List<BlockPos> p = finder.findPath(level, player.blockPosition(), spot);
            if (p != null && p.size() >= 2) {
                double s = c.score + visW + W_REACH_PATH;
                if (s > bestScore) {
                    bestScore = s;
                    best = new Target(c.pos, p, s, false);
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- vein detection

    /**
     * Scans the loaded blocks in the configured radius and groups connected target
     * blocks (26-neighbour adjacency) into veins, then returns the best-scoring one
     * that has at least one exposed (mineable) block — or null if none.
     */
    private Vein findBestVein(ClientLevel level, LocalPlayer player) {
        long now = System.currentTimeMillis();
        blacklist.values().removeIf(expiry -> now >= expiry);

        BlockPos center = scanCenter(player);
        int radius = config.settings.minerScanRadius;
        int vertical = Math.min(radius, SCAN_VERTICAL);
        Set<Long> visited = new HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        Vein best = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -vertical; dy <= vertical; dy++) {
                    int x = center.getX() + dx;
                    int y = center.getY() + dy;
                    int z = center.getZ() + dz;
                    long key = BlockPos.asLong(x, y, z);
                    if (visited.contains(key)) {
                        continue;
                    }
                    m.set(x, y, z);
                    if (blacklist.containsKey(m) || !matches(level.getBlockState(m))) {
                        visited.add(key);
                        continue;
                    }
                    List<BlockPos> blocks = floodFill(level, new BlockPos(x, y, z), visited, center, radius, vertical);
                    Vein vein = buildVein(level, player, blocks);
                    if (vein.accessibility > 0.0 && (best == null || vein.score > best.score)) {
                        best = vein;
                    }
                }
            }
        }
        return best;
    }

    /** Flood-fills connected matching (non-blacklisted) blocks within the scan box. */
    private List<BlockPos> floodFill(ClientLevel level, BlockPos seed, Set<Long> visited,
                                     BlockPos center, int radius, int vertical) {
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
                                || Math.abs(nz - center.getZ()) > radius
                                || Math.abs(ny - center.getY()) > vertical) {
                            continue;
                        }
                        long key = BlockPos.asLong(nx, ny, nz);
                        if (visited.contains(key)) {
                            continue;
                        }
                        visited.add(key);
                        BlockPos np = new BlockPos(nx, ny, nz);
                        if (!blacklist.containsKey(np) && matches(level.getBlockState(np))) {
                            queue.add(np);
                        }
                    }
                }
            }
        }
        return blocks;
    }

    /** Computes a vein's center, distance, value and accessibility, then scores it. */
    private Vein buildVein(ClientLevel level, LocalPlayer player, List<BlockPos> blocks) {
        double sx = 0, sy = 0, sz = 0, value = 0;
        int exposed = 0;
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double nearestSq = Double.MAX_VALUE;
        for (BlockPos b : blocks) {
            sx += b.getX() + 0.5;
            sy += b.getY() + 0.5;
            sz += b.getZ() + 0.5;
            value += blockValue(level.getBlockState(b).getBlock());
            if (isExposed(level, b)) {
                exposed++;
            }
            double d = (b.getX() + 0.5 - px) * (b.getX() + 0.5 - px)
                    + (b.getY() + 0.5 - py) * (b.getY() + 0.5 - py)
                    + (b.getZ() + 0.5 - pz) * (b.getZ() + 0.5 - pz);
            if (d < nearestSq) {
                nearestSq = d;
            }
        }
        int n = blocks.size();
        BlockPos centerPos = BlockPos.containing(sx / n, sy / n, sz / n);
        double distance = Math.sqrt(nearestSq);
        double accessibility = (double) exposed / n;
        // Bigger / more valuable / more accessible / closer veins score higher.
        double score = (value + n * VEIN_SIZE_BONUS) * (0.5 + 0.5 * accessibility) / (distance + VEIN_DIST_BIAS);
        return new Vein(blocks, centerPos, distance, value, accessibility, score);
    }

    /** Relative mined value per block type (for ranking veins). */
    private static double blockValue(Block b) {
        if (b == Blocks.DIAMOND_BLOCK) {
            return 10.0;
        }
        if (b == Blocks.EMERALD_BLOCK) {
            return 9.0;
        }
        if (b == Blocks.IRON_BLOCK) {
            return 6.0;
        }
        if (b == Blocks.COAL_BLOCK) {
            return 3.0;
        }
        if (b == Blocks.QUARTZ_BLOCK) {
            return 2.0;
        }
        return 1.0;
    }

    /** Drops mined / blacklisted / no-longer-matching blocks from the committed vein. */
    private void pruneVein(ClientLevel level) {
        currentVein.removeIf(b -> {
            BlockState st = level.getBlockState(b);
            return st.isAir() || !matches(st) || blacklist.containsKey(b);
        });
    }

    /** Gives up on the current vein: blacklists its remaining blocks so we don't re-pick it. */
    private void abandonVein() {
        long expiry = System.currentTimeMillis() + BLACKLIST_MS;
        for (BlockPos b : currentVein) {
            blacklist.put(b, expiry);
        }
        currentVein.clear();
    }

    /** Counts matching target blocks in the 3×3×3 around {@code pos} (cluster factor). */
    private int countCluster(ClientLevel level, BlockPos pos) {
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (matches(level.getBlockState(m))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean repath(Minecraft client, LocalPlayer player, ClientLevel level) {
        BlockPos spot = findMiningSpot(level, player, target);
        if (spot == null || spot.equals(player.blockPosition())) {
            return false;
        }
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> p = finder.findPath(level, player.blockPosition(), spot);
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
        // Note: crouch is intentionally left as-is — SEEKING keeps it held so the
        // player stays crouched between consecutive mines (no per-block flicker).
        movement.setAttack(client, false);
        target = null;
        currentScore = 0;
        scanTimer = 0; // re-scan immediately
        state = State.SEEKING;
    }

    private void skipTarget(Minecraft client) {
        if (target != null) {
            blacklist.put(target, System.currentTimeMillis() + BLACKLIST_MS);
        }
        nextTarget(client);
    }

    private void halt(Minecraft client) {
        movement.setAttack(client, false);
        movement.setCrouch(client, false);
        movement.releaseAll(client);
        rotation.setActive(false);
        state = State.IDLE;
        routeActive = false;
        target = null;
        currentScore = 0;
        path = Collections.emptyList();
        currentVein.clear();
        tunnelCells = Collections.emptyList();
        patternIndex = 0;
    }

    // ---------------------------------------------------------------- helpers

    private double range() {
        return config.settings.minerRange;
    }

    private void aimAtTarget(LocalPlayer player, ClientLevel level) {
        double minX = 0.0, minY = 0.0, minZ = 0.0, maxX = 1.0, maxY = 1.0, maxZ = 1.0;
        VoxelShape shape = level.getBlockState(target).getShape(level, target);
        if (!shape.isEmpty()) {
            AABB b = shape.bounds();
            minX = b.minX;
            minY = b.minY;
            minZ = b.minZ;
            maxX = b.maxX;
            maxY = b.maxY;
            maxZ = b.maxZ;
        }
        // Aim within the central 60% of the block face — from the center out to (but
        // never reaching) the edges. The offset is rolled per target (in adopt) so
        // the aim stays steady while mining instead of jittering each tick.
        double lx = (minX + maxX) / 2.0 + aimFracX * (maxX - minX);
        double ly = (minY + maxY) / 2.0 + aimFracY * (maxY - minY);
        double lz = (minZ + maxZ) / 2.0 + aimFracZ * (maxZ - minZ);
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

    private double distToTargetSq(LocalPlayer player) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean anyEnabled() {
        ConfigManager.Settings s = config.settings;
        return s.mineDiamondBlock || s.mineIronBlock || s.mineEmeraldBlock || s.mineBlueWool
                || s.mineGrayWool || s.mineGrayTerracotta || s.mineQuartzBlock || s.minePrismarine
                || s.mineDarkPrismarine || s.minePrismarineBricks || s.mineLogs || s.mineCoalBlock
                || s.mineGlass;
    }

    /**
     * True if at least one face of the block borders air — a coarse pre-filter
     * to drop fully-buried blocks before the (more expensive) line-of-sight
     * check decides whether a face is actually mineable.
     */
    private static boolean isExposed(ClientLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (level.getBlockState(pos.relative(d)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if the player can actually see one of the block's air-facing faces
     * from their current eye position (raycast using OUTLINE, so grass/plants
     * and full blocks in the way correctly block it). This is what stops the
     * miner from targeting blocks covered by grass or buried under other blocks.
     */
    private boolean hasLineOfSight(ClientLevel level, LocalPlayer player, BlockPos pos) {
        return canMineFrom(level, player, player.getEyePosition(), pos, Double.MAX_VALUE);
    }

    /**
     * True if, from {@code eye}, at least one air-facing face of the block is
     * within {@code maxReach} and has a clear line (raycast). Used both for the
     * current view and for evaluating candidate standing spots to strafe to.
     */
    private boolean canMineFrom(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos, double maxReach) {
        double maxSq = maxReach >= Double.MAX_VALUE ? Double.MAX_VALUE : maxReach * maxReach;
        for (Direction d : Direction.values()) {
            if (!level.getBlockState(pos.relative(d)).isAir()) {
                continue; // only faces that open onto air are worth checking
            }
            Vec3 faceCenter = new Vec3(
                    pos.getX() + 0.5 + d.getStepX() * 0.5,
                    pos.getY() + 0.5 + d.getStepY() * 0.5,
                    pos.getZ() + 0.5 + d.getStepZ() * 0.5);
            if (eye.distanceToSqr(faceCenter) > maxSq) {
                continue;
            }
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, faceCenter, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the nearest standable spot from which {@code block} can be mined
     * (in reach + line of sight). This lets the miner strafe/reposition within
     * the area to break blocks it can't reach from where it currently stands.
     * Returns null if no such spot exists nearby.
     */
    private BlockPos findMiningSpot(ClientLevel level, LocalPlayer player, BlockPos block) {
        double range = range();
        double eyeH = player.getEyeY() - player.getY();
        BlockPos pp = player.blockPosition();
        int r = (int) Math.ceil(range) + 1;
        BlockPos best = null;
        long bestSq = Long.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos spot = block.offset(dx, dy, dz);
                    if (!BaritonePathfinder.isStandable(level, spot)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(spot.getX() + 0.5, spot.getY() + eyeH, spot.getZ() + 0.5);
                    if (!canMineFrom(level, player, eye, block, range)) {
                        continue;
                    }
                    long d = (long) (spot.getX() - pp.getX()) * (spot.getX() - pp.getX())
                            + (long) (spot.getY() - pp.getY()) * (spot.getY() - pp.getY())
                            + (long) (spot.getZ() - pp.getZ()) * (spot.getZ() - pp.getZ());
                    if (d < bestSq) {
                        bestSq = d;
                        best = spot;
                    }
                }
            }
        }
        return best;
    }

    private boolean matches(BlockState state) {
        ConfigManager.Settings s = config.settings;
        Block b = state.getBlock();
        if (s.mineDiamondBlock && b == Blocks.DIAMOND_BLOCK) return true;
        if (s.mineIronBlock && b == Blocks.IRON_BLOCK) return true;
        if (s.mineEmeraldBlock && b == Blocks.EMERALD_BLOCK) return true;
        if (s.mineBlueWool && b == Blocks.BLUE_WOOL) return true;
        if (s.mineGrayWool && b == Blocks.GRAY_WOOL) return true;
        if (s.mineGrayTerracotta && b == Blocks.GRAY_TERRACOTTA) return true;
        if (s.mineQuartzBlock && b == Blocks.QUARTZ_BLOCK) return true;
        if (s.minePrismarine && b == Blocks.PRISMARINE) return true;
        if (s.mineDarkPrismarine && b == Blocks.DARK_PRISMARINE) return true;
        if (s.minePrismarineBricks && b == Blocks.PRISMARINE_BRICKS) return true;
        if (s.mineCoalBlock && b == Blocks.COAL_BLOCK) return true;
        if (s.mineGlass && GLASS.contains(b)) return true; // glass blocks + panes, any colour
        return s.mineLogs && state.is(BlockTags.LOGS, st -> true);
    }

    private record Scored(BlockPos pos, double score, double dist) {
    }

    /** A connected group of target blocks (an ore vein / tree) and its metrics. */
    private record Vein(List<BlockPos> blocks, BlockPos center, double distance,
                        double value, double accessibility, double score) {
    }

    private record Target(BlockPos pos, List<BlockPos> path, double score, boolean inReach) {
    }
}
