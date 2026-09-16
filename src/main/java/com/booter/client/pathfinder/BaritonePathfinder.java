package com.booter.client.pathfinder;

import com.booter.client.pathdebug.PathDebugManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A* pathfinder modelled on Baritone's architecture:
 *
 * <ul>
 *   <li><b>Movement cost model</b> in game ticks (walk/water speeds, a jump
 *       penalty for ascends, a precomputed fall-time table for descends, and a
 *       take-off penalty for parkour) — cheaper moves are preferred just like
 *       Baritone's {@code ActionCosts}.</li>
 *   <li><b>Node expansion</b> via discrete movement operators per direction —
 *       Traverse, Diagonal, Ascend, Descend/Fall and Parkour — each validating
 *       feasibility against the world and returning a cost (Baritone's
 *       {@code Moves}/{@code Movement}).</li>
 *   <li><b>A*</b> with an admissible octile heuristic and a custom binary-heap
 *       open set with decrease-key (Baritone's {@code BinaryHeapOpenSet}),
 *       bounded by a node budget, a search radius and a time budget.</li>
 * </ul>
 *
 * <p>It only walks — it never breaks or places blocks — and avoids lava/fire and
 * fences/walls (via {@link BlockState#isPathfindable}). Resulting paths are
 * collapsed to their turning points.
 */
public final class BaritonePathfinder {
    // --- movement costs, in ticks (20 ticks = 1s) — Baritone ActionCosts inspired ---
    private static final double WALK = 20.0 / 4.317;        // player walk speed ≈ 4.633
    private static final double SPRINT = 20.0 / 5.612;      // sprint speed ≈ 3.564 — the default
    private static final double WALK_WATER = 20.0 / 2.2;    // ≈ 9.091
    private static final double SQRT2 = 1.4142135623730951;
    private static final double ASCEND_EXTRA = 5.0;         // extra ticks to jump up a full block
    private static final double STEP_UP = 1.5;              // auto-step up a slab/stair (no jump)
    /** Max collision height (blocks) a block can be to auto-step onto it (vanilla step ≈0.6). */
    private static final double AUTO_STEP_MAX = 0.55;
    private static final double PARKOUR_JUMP = 4.0;         // take-off penalty for a gap jump
    private static final int MAX_FALL = 3;                  // no fall damage
    private static final int MAX_PARKOUR = 4;               // furthest gap we'll jump
    /** Extra cost per nearby solid block (weighted by closeness): a gentle bias to
     *  keep off walls/corners without distorting routes (Baritone-style efficiency). */
    private static final double CLEARANCE_COST = 1.4;
    /** Strong penalty for walking beside a drop/edge; safer center routes win. */
    private static final double EDGE_COST = 12.0;
    /** How far out (blocks) the clearance scan looks for walls. */
    private static final int CLEARANCE_RADIUS = 2;
    /** Weighted-A* heuristic factor (f = g + w·h). &gt;1 trades a little optimality
     *  for a much faster search over long distances (V5-inspired). */
    private static final double DEFAULT_HEURISTIC_WEIGHT = 1.05;
    /** Max nodes a string-pull segment may span (keeps segments sane). */
    private static final int MAX_SMOOTH_SKIP = 18;
    /** Max horizontal length (blocks) of a string-pulled straight segment. */
    private static final double MAX_SMOOTH_DIST = 24.0;
    /** FALL_COST[n] = ticks to fall n blocks (simulated with MC gravity). */
    private static final double[] FALL_COST = new double[MAX_FALL + 1];

    static {
        double v = 0.0;
        double fallen = 0.0;
        int t = 0;
        for (int n = 1; n <= MAX_FALL; n++) {
            while (fallen < n) {
                v = (v - 0.08) * 0.98;
                fallen += -v;
                t++;
            }
            FALL_COST[n] = t;
        }
    }

    private static final int[][] CARDINALS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final int[][] DIAGONALS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    private final int maxNodes;
    private final int maxRadius;
    private final boolean allowWater;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private BlockGetter level;
    private BlockPos[] goals = new BlockPos[0];
    private double heuristicWeight = DEFAULT_HEURISTIC_WEIGHT;
    private List<AvoidZone> avoidZones = List.of();

    // Live search visualization — populated only while the debug overlay is on.
    private PathDebugManager debug;
    private boolean reportDebug;

    public BaritonePathfinder(int maxNodes, int maxRadius, boolean allowWater) {
        this.maxNodes = maxNodes;
        this.maxRadius = maxRadius;
        this.allowWater = allowWater;
    }

    /**
     * Weighted-A* factor: f = g + weight·h. {@code weight == 1} is optimal but slow;
     * a little above 1 (default {@value #DEFAULT_HEURISTIC_WEIGHT}) searches much
     * faster over long distances for near-optimal paths. Returns {@code this}.
     */
    public BaritonePathfinder setHeuristicWeight(double weight) {
        this.heuristicWeight = Math.max(1.0, weight);
        return this;
    }

    /** Penalty regions the search routes around (hazards, no-go areas). Returns {@code this}. */
    public BaritonePathfinder setAvoidZones(List<AvoidZone> zones) {
        this.avoidZones = zones == null ? List.of() : zones;
        return this;
    }

    /**
     * A circular penalty region: nodes within {@code radius} horizontally (and
     * {@code maxYDiff} vertically) of (x,y,z) cost an extra {@code penalty} in ticks,
     * steering paths around it without making it impassable.
     */
    public record AvoidZone(int x, int y, int z, double radius, int maxYDiff, double penalty) {
        double radiusSq() {
            return radius * radius;
        }
    }

    /**
     * @return the path (turning points) from start to a standable position at the
     *         goal, or {@code null} if the goal can't be reached within the budget.
     */
    public List<BlockPos> findPath(BlockGetter level, BlockPos start, BlockPos rawGoal) {
        return findPath(level, start, List.of(rawGoal));
    }

    /**
     * Multi-goal search: finds the cheapest path (turning points) from {@code start}
     * to a standable position at <em>any</em> of {@code rawGoals}, or {@code null} if
     * none is reachable within the budget.
     */
    public List<BlockPos> findPath(BlockGetter level, BlockPos start, List<BlockPos> rawGoals) {
        this.level = level;
        List<BlockPos> resolvedGoals = new ArrayList<>();
        for (BlockPos g : rawGoals) {
            if (g == null) {
                continue;
            }
            BlockPos r = resolveGoal(g);
            if (r != null) {
                resolvedGoals.add(r);
            }
        }
        if (resolvedGoals.isEmpty()) {
            return null;
        }
        this.goals = resolvedGoals.toArray(new BlockPos[0]);

        debug = PathDebugManager.get();
        reportDebug = debug.settings.enabled;
        if (reportDebug) {
            debug.beginSearch(this.goals[0]);
        }

        Map<Long, Node> nodes = new HashMap<>();
        OpenSet open = new OpenSet();
        Node startNode = new Node(start.getX(), start.getY(), start.getZ());
        startNode.cost = 0.0;
        startNode.estimate = heuristic(startNode.x, startNode.y, startNode.z);
        startNode.combined = startNode.estimate * heuristicWeight;
        nodes.put(startNode.key, startNode);
        open.insert(startNode);
        if (reportDebug) {
            debug.addOpen(startNode.key);
        }

        long deadline = System.nanoTime() + 40_000_000L; // 40 ms budget
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.removeLowest();
            if (reportDebug) {
                debug.addClosed(current.key);
            }
            if (isGoal(current.x, current.y, current.z)) {
                List<BlockPos> path = finishPath(current);
                if (reportDebug) {
                    debug.setFinalPath(path);
                    debug.endSearch();
                }
                return path;
            }
            expanded++;
            if ((expanded & 63) == 0 && System.nanoTime() > deadline) {
                break;
            }
            expand(current, nodes, open, start);
        }
        if (reportDebug) {
            debug.endSearch();
        }
        return null;
    }

    // ------------------------------------------------------------- node expansion

    private void expand(Node n, Map<Long, Node> nodes, OpenSet open, BlockPos start) {
        int x = n.x;
        int y = n.y;
        int z = n.z;

        for (int[] dir : CARDINALS) {
            int dx = dir[0];
            int dz = dir[1];

            if (isStandable(x + dx, y, z + dz)) {
                // Traverse: flat step onto solid ground.
                relax(nodes, open, n, x + dx, y, z + dz, moveBase(x + dx, y, z + dz), start);
                continue;
            }
            // Ascend: step/jump up one level onto a full block, slab or stair.
            // isStandable handles the landing for all three (including auto-step
            // blocks whose own cell isn't "passable"), so stairs/slabs are climbable.
            if (passable(x, y + 2, z) && isStandable(x + dx, y + 1, z + dz)) {
                // Auto-stepping up a slab/stair needs no jump, so it's cheaper than a
                // full-block hop — this makes stair/slab routes preferred when natural.
                double up = autoStep(x + dx, y + 1, z + dz) ? STEP_UP : ASCEND_EXTRA;
                relax(nodes, open, n, x + dx, y + 1, z + dz,
                        moveBase(x + dx, y + 1, z + dz) + up, start);
                continue;
            }
            // Descend / fall: step off the edge and drop to the first standable
            // landing — isStandable picks the correct feet level for full blocks,
            // slabs and stairs alike, so descending slab/stair runs stay monotonic.
            if (passable(x + dx, y, z + dz) && passable(x + dx, y + 1, z + dz)) {
                boolean descended = false;
                for (int drop = 1; drop <= MAX_FALL; drop++) {
                    int feetY = y - drop;
                    if (isStandable(x + dx, feetY, z + dz)) {
                        relax(nodes, open, n, x + dx, feetY, z + dz,
                                moveBase(x + dx, feetY, z + dz) + FALL_COST[drop], start);
                        descended = true;
                        break;
                    }
                    if (!passable(x + dx, feetY, z + dz)) {
                        break; // solid block — a landing on top of it was caught already
                    }
                }
                // Parkour: sprint-jump a gap to flat ground further out.
                if (!descended && passable(x + dx, y - 1, z + dz)) {
                    for (int dist = 2; dist <= MAX_PARKOUR; dist++) {
                        int lx = x + dx * dist;
                        int lz = z + dz * dist;
                        if (!passable(x + dx * (dist - 1), y, z + dz * (dist - 1))
                                || !passable(x + dx * (dist - 1), y + 1, z + dz * (dist - 1))) {
                            break; // run-up blocked
                        }
                        if (isStandable(lx, y, lz)) {
                            relax(nodes, open, n, lx, y, lz,
                                    moveBase(lx, y, lz) * dist + PARKOUR_JUMP, start);
                            break;
                        }
                    }
                }
            }
        }

        for (int[] dir : DIAGONALS) {
            int dx = dir[0];
            int dz = dir[1];
            // Diagonal traverse, with corner-cut prevention (both orthogonal cells open).
            if (isStandable(x + dx, y, z + dz)
                    && passable(x + dx, y, z) && passable(x + dx, y + 1, z)
                    && passable(x, y, z + dz) && passable(x, y + 1, z + dz)) {
                relax(nodes, open, n, x + dx, y, z + dz, moveBase(x + dx, y, z + dz) * SQRT2, start);
            }
        }
    }

    private void relax(Map<Long, Node> nodes, OpenSet open, Node from,
                       int nx, int ny, int nz, double moveCost, BlockPos start) {
        long ddx = nx - start.getX();
        long ddz = nz - start.getZ();
        if (ddx * ddx + ddz * ddz > (long) maxRadius * maxRadius) {
            return;
        }
        long key = BlockPos.asLong(nx, ny, nz);
        Node nb = nodes.get(key);
        if (nb == null) {
            nb = new Node(nx, ny, nz);
            nb.clearance = clearancePenalty(nx, ny, nz);
            nb.edge = edgeRisk(nx, ny, nz);
            nb.avoid = avoidPenalty(nx, ny, nz);
            nodes.put(key, nb);
        }
        double tentative = from.cost + moveCost + nb.clearance + nb.edge + nb.avoid;
        if (tentative < nb.cost) {
            nb.cost = tentative;
            nb.previous = from;
            nb.estimate = heuristic(nx, ny, nz);
            nb.combined = tentative + nb.estimate * heuristicWeight;
            if (nb.heapPosition >= 0) {
                open.update(nb);
            } else {
                open.insert(nb);
                if (reportDebug) {
                    debug.addOpen(nb.key);
                }
            }
        }
    }

    // ------------------------------------------------------------- heuristic / goal

    /** Octile distance × cheapest per-block cost to the NEAREST goal (vertical ignored). */
    private double heuristic(int x, int y, int z) {
        double best = Double.MAX_VALUE;
        for (BlockPos g : goals) {
            int dx = Math.abs(g.getX() - x);
            int dz = Math.abs(g.getZ() - z);
            int diag = Math.min(dx, dz);
            int straight = Math.max(dx, dz) - diag;
            double h = SPRINT * (straight + SQRT2 * diag);
            if (h < best) {
                best = h;
            }
        }
        return best;
    }

    private boolean isGoal(int x, int y, int z) {
        for (BlockPos g : goals) {
            if (x == g.getX() && z == g.getZ() && Math.abs(y - g.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** Sum of penalties for any avoid-zones this node falls inside. */
    private double avoidPenalty(int x, int y, int z) {
        if (avoidZones.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (AvoidZone zone : avoidZones) {
            if (Math.abs(y - zone.y()) > zone.maxYDiff()) {
                continue;
            }
            long dx = x - zone.x();
            long dz = z - zone.z();
            if (dx * dx + dz * dz <= zone.radiusSq()) {
                total += zone.penalty();
            }
        }
        return total;
    }

    private BlockPos resolveGoal(BlockPos g) {
        if (isStandable(g.getX(), g.getY(), g.getZ())) {
            return g;
        }
        BlockPos best = null;
        long bestSq = Long.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (isStandable(g.getX() + dx, g.getY() + dy, g.getZ() + dz)) {
                        long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                        if (d < bestSq) {
                            bestSq = d;
                            best = g.offset(dx, dy, dz);
                        }
                    }
                }
            }
        }
        return best;
    }

    private List<BlockPos> finishPath(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.previous) {
            path.add(new BlockPos(n.x, n.y, n.z));
        }
        Collections.reverse(path);
        return stringPull(simplify(path));
    }

    /**
     * Line-of-sight string pull: collapses runs of nodes into long straight
     * segments wherever the player could walk a straight line between them, so
     * paths over long / rough terrain end up with far fewer nodes and look clean.
     * It greedily keeps the farthest node reachable in a straight walkable line
     * (bounded by {@link #MAX_SMOOTH_SKIP} and {@link #MAX_SMOOTH_DIST}), and only
     * keeps intermediate nodes where the terrain actually forces a turn.
     */
    private List<BlockPos> stringPull(List<BlockPos> path) {
        if (path.size() <= 2) {
            return path;
        }
        List<BlockPos> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < path.size() - 1) {
            int limit = Math.min(path.size() - 1, i + MAX_SMOOTH_SKIP);
            int next = i + 1;
            for (int j = limit; j > i + 1; j--) {
                if (withinSmoothDist(path.get(i), path.get(j)) && walkableLine(path.get(i), path.get(j))) {
                    next = j;
                    break;
                }
            }
            out.add(path.get(next));
            i = next;
        }
        return out;
    }

    private static boolean withinSmoothDist(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return dx * dx + dz * dz <= MAX_SMOOTH_DIST * MAX_SMOOTH_DIST;
    }

    /**
     * True if the player could walk a straight line from {@code a} to {@code b}:
     * sampling along the segment, every column must have a standable spot near the
     * linearly-interpolated height. Walls (no standable at that height) and pits
     * (no ground) make it false, so we never straight-line through them.
     */
    private boolean walkableLine(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            return Math.abs(dy) <= 1;
        }
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            int bx = (int) Math.floor(a.getX() + 0.5 + dx * t);
            int bz = (int) Math.floor(a.getZ() + 0.5 + dz * t);
            int by = (int) Math.round(a.getY() + dy * t);
            // Allow a ±1 band so gentle slopes/steps the player auto-handles still pass.
            if (!isStandable(bx, by, bz) && !isStandable(bx, by + 1, bz) && !isStandable(bx, by - 1, bz)) {
                return false;
            }
            if (edgeRisk(bx, by, bz) > 0.0) {
                return false;
            }
        }
        return true;
    }

    /** Collapse collinear runs so only turning points remain. */
    private List<BlockPos> simplify(List<BlockPos> path) {
        if (path.size() <= 2) {
            return path;
        }
        List<BlockPos> out = new ArrayList<>();
        out.add(path.get(0));
        for (int i = 1; i + 1 < path.size(); i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos cur = path.get(i);
            BlockPos next = path.get(i + 1);
            int ax = cur.getX() - prev.getX();
            int ay = cur.getY() - prev.getY();
            int az = cur.getZ() - prev.getZ();
            int bx = next.getX() - cur.getX();
            int by = next.getY() - cur.getY();
            int bz = next.getZ() - cur.getZ();
            long cx = (long) ay * bz - (long) az * by;
            long cy = (long) az * bx - (long) ax * bz;
            long cz = (long) ax * by - (long) ay * bx;
            long dot = (long) ax * bx + (long) ay * by + (long) az * bz;
            if (edgeRisk(cur.getX(), cur.getY(), cur.getZ()) > 0.0
                    || !(cx == 0 && cy == 0 && cz == 0 && dot > 0)) {
                out.add(cur);
            }
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    // ------------------------------------------------------------- walkability

    private double moveBase(int x, int y, int z) {
        // The follower sprints, so cost paths at sprint speed (Baritone does the
        // same) — this yields direct, natural routes rather than timid walking ones.
        return isWater(x, y, z) ? WALK_WATER : SPRINT;
    }

    /**
     * Penalty for hugging collisions: scans solid blocks in a {@link #CLEARANCE_RADIUS}
     * neighbourhood at foot and head level, weighting closer walls more heavily, so
     * the search strongly prefers the middle of open space and stays centered in
     * corridors and rooms (not just one block off a wall). In a tight 1-wide passage
     * every cell scores the same, so the path there is unchanged; with any room to
     * spare it bows to the center, which keeps the player off block edges/corners.
     *
     * <p>Computed once per node (cached on the {@link Node}), so the wider scan does
     * not slow the search.
     */
    private double clearancePenalty(int x, int y, int z) {
        double penalty = 0.0;
        for (int dx = -CLEARANCE_RADIUS; dx <= CLEARANCE_RADIUS; dx++) {
            for (int dz = -CLEARANCE_RADIUS; dz <= CLEARANCE_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (isClearanceWall(x + dx, y, z + dz) || isClearanceWall(x + dx, y + 1, z + dz)) {
                    // Chebyshev distance 1 (adjacent) weighs full; distance 2 weighs half.
                    int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                    penalty += CLEARANCE_COST / cheb;
                }
            }
        }
        return penalty;
    }

    private double edgeRisk(int x, int y, int z) {
        double penalty = 0.0;
        for (int[] dir : CARDINALS) {
            if (isDropEdge(x + dir[0], y, z + dir[1])) {
                penalty += EDGE_COST;
            }
        }
        for (int[] dir : DIAGONALS) {
            if (isDropEdge(x + dir[0], y, z + dir[1])) {
                penalty += EDGE_COST * 0.5;
            }
        }
        return penalty;
    }

    private boolean isDropEdge(int x, int y, int z) {
        if (!passable(x, y, z) || !passable(x, y + 1, z)) {
            return false;
        }
        for (int drop = 1; drop <= 3; drop++) {
            if (canWalkOn(x, y - drop, z)) {
                return drop >= 2;
            }
            if (!passable(x, y - drop, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A block that counts as a snag-able "wall" for clearance scoring: it has
     * collision and is NOT a walkable low step (stairs / bottom slab). Without the
     * exception, slab/stair surfaces score like obstacles and the planner avoids
     * routing over them.
     */
    private boolean isClearanceWall(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        VoxelShape shape = state.getCollisionShape(level, cursor);
        if (shape.isEmpty()) {
            return false; // air / passable → not a wall
        }
        if (state.getBlock() instanceof StairBlock) {
            return false; // walkable
        }
        return shape.max(Direction.Axis.Y) > AUTO_STEP_MAX; // tall = wall, low step = walkable
    }

    private boolean isWater(int x, int y, int z) {
        return level.getBlockState(cursor.set(x, y, z)).getBlock() == Blocks.WATER;
    }

    /** No collision in the way, not a hazard, not a fence/wall. */
    private boolean passable(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        Block b = state.getBlock();
        // Water FIRST: it has an empty collision shape and reports pathfindable, so
        // without this gate it would be treated as freely walkable. Avoid it unless
        // swimming is explicitly enabled. (Also catches flowing water.)
        if (b == Blocks.WATER || !state.getFluidState().isEmpty()) {
            return allowWater && state.getCollisionShape(level, cursor).isEmpty();
        }
        if (b == Blocks.LAVA || b == Blocks.FIRE) {
            return false;
        }
        // Anything with no collision box is walkable — air, and crucially crops,
        // grass, flowers, torches, etc. (crops report NOT pathfindable so mobs avoid
        // trampling them, but we want to walk straight through as if empty).
        return state.getCollisionShape(level, cursor).isEmpty();
    }

    /** Can the player stand on top of the block at (x,y,z)? */
    private boolean canWalkOn(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (state.getBlock() == Blocks.LAVA) {
            return false;
        }
        // Stairs have a partial top so isFaceSturdy(UP) is false, but you can stand
        // on them — accept them (bottom slabs already report a sturdy top face).
        if (state.isFaceSturdy(level, cursor, Direction.UP) || isStairs(state)) {
            return true;
        }
        // Near-full solid tops (farmland, dirt path — 15/16) aren't "sturdy" but you
        // can stand on them; accept any solid top tall enough to walk on.
        VoxelShape shape = state.getCollisionShape(level, cursor);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.85;
    }

    private boolean isStandable(int x, int y, int z) {
        // Normal: stand on top of a FULL-height floor (feet at this block). The floor
        // must NOT be a half-block (slab/stair) — those are stood on in-block via the
        // auto-step branch below, so each slab/stair has exactly one feet level (no
        // dual representation, which is what made descending slab runs zigzag).
        if (canWalkOn(x, y - 1, z) && !autoStep(x, y - 1, z)
                && passable(x, y, z) && passable(x, y + 1, z)) {
            return true;
        }
        // Auto-step: the feet block itself is a low step (stairs / bottom slab /
        // carpet / etc.) that we stand on top of — vanilla's 0.6 step height lifts us
        // onto it. We only need headroom above; the step block is its own support.
        return autoStep(x, y, z) && passable(x, y + 1, z);
    }

    private static boolean isStairs(BlockState state) {
        return state.getBlock() instanceof StairBlock;
    }

    /**
     * A low block vanilla auto-steps onto without a jump (≤{@value #AUTO_STEP_MAX}
     * tall): bottom slabs, carpets, snow layers, etc., plus stairs. Detected from the
     * collision shape rather than the block class, so it works for every such block
     * (including custom ones) — this is what makes slab/stair routes navigable.
     */
    private boolean autoStep(int x, int y, int z) {
        return autoStepShape(level, cursor.set(x, y, z));
    }

    /** True if vanilla auto-steps onto the block at {@code pos} without a jump
     *  (stairs, bottom slabs, carpets, snow, …). For callers gating auto-jump. */
    public static boolean isAutoStep(BlockGetter level, BlockPos pos) {
        return autoStepShape(level, pos);
    }

    private static boolean autoStepShape(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof StairBlock) {
            return true; // stairs are full-height on one side but still auto-steppable
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= AUTO_STEP_MAX;
    }

    /** Static standability check (no water) for callers picking standing/mining spots. */
    public static boolean isStandable(BlockGetter level, BlockPos pos) {
        if (canWalkOnStatic(level, pos.below()) && !autoStepShape(level, pos.below())
                && passableStatic(level, pos) && passableStatic(level, pos.above())) {
            return true;
        }
        // Auto-step onto a stair / bottom slab / low block at this position.
        return autoStepShape(level, pos) && passableStatic(level, pos.above());
    }

    private static boolean passableStatic(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block b = state.getBlock();
        // Never treat water (or any fluid) as walkable for the static checks.
        if (b == Blocks.WATER || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (b == Blocks.LAVA || b == Blocks.FIRE) {
            return false;
        }
        // No collision box → walkable (air, crops, grass, flowers, …).
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean canWalkOnStatic(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() == Blocks.LAVA) {
            return false;
        }
        if (state.isFaceSturdy(level, pos, Direction.UP) || isStairs(state)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.85;
    }

    // ------------------------------------------------------------- data structures

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final long key;
        double cost = Double.MAX_VALUE;     // g
        double estimate;                    // h
        double combined = Double.MAX_VALUE; // f = g + h
        double clearance;                   // cached collision-proximity penalty
        double edge;                        // cached nearby-drop penalty
        double avoid;                       // cached avoid-zone penalty
        Node previous;
        int heapPosition = -1;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = BlockPos.asLong(x, y, z);
        }
    }

    /** Binary min-heap keyed by {@code combined} (f), with decrease-key support. */
    private static final class OpenSet {
        private Node[] heap = new Node[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void insert(Node node) {
            if (size >= heap.length) {
                heap = Arrays.copyOf(heap, heap.length * 2);
            }
            heap[size] = node;
            node.heapPosition = size;
            size++;
            siftUp(size - 1);
        }

        /** Call after a node's combined cost was lowered. */
        void update(Node node) {
            siftUp(node.heapPosition);
        }

        Node removeLowest() {
            Node root = heap[0];
            root.heapPosition = -1;
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                heap[0].heapPosition = 0;
                heap[size] = null;
                siftDown(0);
            } else {
                heap[0] = null;
            }
            return root;
        }

        private void siftUp(int i) {
            Node node = heap[i];
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (heap[parent].combined <= node.combined) {
                    break;
                }
                heap[i] = heap[parent];
                heap[i].heapPosition = i;
                i = parent;
            }
            heap[i] = node;
            node.heapPosition = i;
        }

        private void siftDown(int i) {
            int half = size >> 1;
            Node node = heap[i];
            while (i < half) {
                int child = (i << 1) + 1;
                int right = child + 1;
                if (right < size && heap[right].combined < heap[child].combined) {
                    child = right;
                }
                if (heap[child].combined >= node.combined) {
                    break;
                }
                heap[i] = heap[child];
                heap[i].heapPosition = i;
                i = child;
            }
            heap[i] = node;
            node.heapPosition = i;
        }
    }
}
