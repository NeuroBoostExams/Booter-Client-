package com.booter.client.pathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D A* for swimming through connected water. It searches water cells rather
 * than ground stand-spots, so vertical movement, underwater routes and surface
 * swimming are handled directly. Movement is still executed later with vanilla
 * inputs only.
 */
public final class WaterPathfinder {
    private static final double SQRT2 = Math.sqrt(2.0);
    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double WALL_PENALTY = 4.0;
    private static final double DIST_WEIGHT = 10.0;
    private static final double CLEARANCE_BONUS = 0.75;
    private static final double EDGE_PENALTY = 3.25;
    private static final int OPEN_WATER_CLEARANCE = 56;
    private static final double TURN_PENALTY = 1.1;
    private static final double REVERSE_PENALTY = 8.0;
    private static final int STRAIGHT_CHECKPOINT_SPACING = 4;
    private static final int MAX_SMOOTH_SKIP = 12;
    private static final double MAX_SMOOTH_DIST = 14.0;
    private static final int MIN_SMOOTH_CLEARANCE = 42;

    private final int maxNodes;
    private final int maxRadius;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private BlockGetter level;
    private BlockPos goal;
    private BlockPos avoidCenter;
    private double avoidRadiusSq;
    private double avoidPenalty;

    public WaterPathfinder(int maxNodes, int maxRadius) {
        this.maxNodes = maxNodes;
        this.maxRadius = maxRadius;
    }

    public WaterPathfinder avoidNear(BlockPos center, double radius, double penalty) {
        this.avoidCenter = center;
        this.avoidRadiusSq = radius * radius;
        this.avoidPenalty = penalty;
        return this;
    }

    public List<BlockPos> findPath(BlockGetter level, BlockPos start, BlockPos rawGoal) {
        this.level = level;
        BlockPos s = resolveWaterCell(start, 8);
        BlockPos g = resolveWaterCell(rawGoal, 12);
        if (s == null || g == null) {
            return null;
        }
        this.goal = g;

        Map<Long, Node> nodes = new HashMap<>();
        OpenSet open = new OpenSet();
        Node startNode = new Node(s.getX(), s.getY(), s.getZ());
        startNode.cost = 0.0;
        startNode.combined = heuristic(startNode.x, startNode.y, startNode.z);
        nodes.put(startNode.key, startNode);
        open.insert(startNode);

        long maxRadiusSq = (long) maxRadius * maxRadius;
        long deadline = System.nanoTime() + 10_000_000L; // keep client ticks responsive
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < maxNodes) {
            Node cur = open.removeLowest();
            if (cur.x == goal.getX() && cur.y == goal.getY() && cur.z == goal.getZ()) {
                return simplify(finish(cur));
            }
            if ((expanded & 63) == 0 && System.nanoTime() > deadline) {
                return null;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int nx = cur.x + dx;
                        int ny = cur.y + dy;
                        int nz = cur.z + dz;
                        long rx = nx - s.getX();
                        long rz = nz - s.getZ();
                        if (rx * rx + rz * rz > maxRadiusSq || !waterPassable(nx, ny, nz)) {
                            continue;
                        }
                        // Do not squeeze diagonally through solid corners.
                        if (dx != 0 && !waterPassable(cur.x + dx, cur.y, cur.z)) continue;
                        if (dy != 0 && !waterPassable(cur.x, cur.y + dy, cur.z)) continue;
                        if (dz != 0 && !waterPassable(cur.x, cur.y, cur.z + dz)) continue;

                        double step = stepCost(cur, dx, dy, dz, nx, ny, nz);
                        relax(nodes, open, cur, nx, ny, nz, step);
                    }
                }
            }
        }
        return null;
    }

    private BlockPos resolveWaterCell(BlockPos center, int r) {
        // Common use: user looks at the sea floor. Prefer the water column above
        // that exact x/z so descending does not pick a horizontally offset goal and
        // spin the view around.
        if (!waterPassable(center.getX(), center.getY(), center.getZ())) {
            BlockPos column = resolveWaterAbove(center, Math.max(16, r));
            if (column != null) {
                return column;
            }
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = center.getX() + dx;
                    int y = center.getY() + dy;
                    int z = center.getZ() + dz;
                    if (waterPassable(x, y, z)) {
                        // Prefer open water near the requested position instead of
                        // the closest sliver beside terrain. This is what keeps a
                        // clicked sea-floor / wall block from making the path hug
                        // the nearest solid face when the open ocean is available.
                        int d = dx * dx + dy * dy + dz * dz;
                        double score = d * DIST_WEIGHT - waterClearance(x, y, z) * CLEARANCE_BONUS;
                        if (score < bestScore) {
                            bestScore = score;
                            best = center.offset(dx, dy, dz);
                        }
                    }
                }
            }
        }
        return best;
    }

    private BlockPos resolveWaterAbove(BlockPos center, int up) {
        BlockPos best = null;
        int bestClearance = -1;
        for (int dy = 1; dy <= up; dy++) {
            int y = center.getY() + dy;
            if (!waterPassable(center.getX(), y, center.getZ())) {
                // Keep searching; kelp/terrain columns can be uneven.
                continue;
            }
            int clearance = waterClearance(center.getX(), y, center.getZ());
            if (clearance > bestClearance) {
                bestClearance = clearance;
                best = center.above(dy);
            }
            // If the first few cells above the floor are already reasonably open,
            // stop there; it preserves the user's intended x/z and depth.
            if (dy <= 3 && clearance >= 28) {
                return best;
            }
        }
        return best;
    }

    private void relax(Map<Long, Node> nodes, OpenSet open, Node from, int x, int y, int z, double moveCost) {
        long key = BlockPos.asLong(x, y, z);
        Node n = nodes.get(key);
        if (n == null) {
            n = new Node(x, y, z);
            nodes.put(key, n);
        }
        double next = from.cost + moveCost;
        if (next < n.cost) {
            n.cost = next;
            n.previous = from;
            n.combined = next + heuristic(x, y, z);
            if (n.heapPosition >= 0) {
                open.update(n);
            } else {
                open.insert(n);
            }
        }
    }

    private double stepCost(Node from, int dx, int dy, int dz, int x, int y, int z) {
        int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        double base = axes == 3 ? SQRT3 : axes == 2 ? SQRT2 : 1.0;
        // Rising costs slightly more than sinking; hugging blocks costs more so
        // routes prefer open water instead of scraping walls/terrain.
        double vertical = dy > 0 ? 0.25 : dy < 0 ? 0.08 : 0.0;
        return base + vertical + headingPenalty(from, dx, dy, dz)
                + wallPenalty(x, y, z) + clearancePenalty(x, y, z) + avoidPenalty(x, y, z);
    }

    private double headingPenalty(Node from, int dx, int dy, int dz) {
        if (from.previous == null) {
            return 0.0;
        }
        int px = step(from.x - from.previous.x);
        int py = step(from.y - from.previous.y);
        int pz = step(from.z - from.previous.z);
        if (dx == px && dy == py && dz == pz) {
            return 0.0;
        }
        int dot = dx * px + dy * py + dz * pz;
        return dot < 0 ? REVERSE_PENALTY : TURN_PENALTY;
    }

    private double wallPenalty(int x, int y, int z) {
        double p = 0.0;
        if (!waterPassable(x + 1, y, z)) p += WALL_PENALTY;
        if (!waterPassable(x - 1, y, z)) p += WALL_PENALTY;
        if (!waterPassable(x, y, z + 1)) p += WALL_PENALTY;
        if (!waterPassable(x, y, z - 1)) p += WALL_PENALTY;
        if (!waterPassable(x + 2, y, z)) p += WALL_PENALTY * 0.35;
        if (!waterPassable(x - 2, y, z)) p += WALL_PENALTY * 0.35;
        if (!waterPassable(x, y, z + 2)) p += WALL_PENALTY * 0.35;
        if (!waterPassable(x, y, z - 2)) p += WALL_PENALTY * 0.35;
        if (!waterPassable(x, y + 1, z)) p += WALL_PENALTY * 0.35;
        if (!waterPassable(x, y - 1, z)) p += WALL_PENALTY * 0.15;
        return p;
    }

    private double clearancePenalty(int x, int y, int z) {
        int clearance = waterClearance(x, y, z);
        if (clearance >= OPEN_WATER_CLEARANCE) {
            return 0.0;
        }
        return (OPEN_WATER_CLEARANCE - clearance) * EDGE_PENALTY;
    }

    private double avoidPenalty(int x, int y, int z) {
        if (avoidCenter == null) {
            return 0.0;
        }
        double dx = x - avoidCenter.getX();
        double dy = y - avoidCenter.getY();
        double dz = z - avoidCenter.getZ();
        return dx * dx + dy * dy + dz * dz <= avoidRadiusSq ? avoidPenalty : 0.0;
    }

    /** Count nearby water cells; higher means "more open" water. */
    private int waterClearance(int x, int y, int z) {
        int clear = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (waterPassable(x + dx, y + dy, z + dz)) {
                        clear++;
                    }
                }
            }
        }
        return clear;
    }

    private double heuristic(int x, int y, int z) {
        double dx = goal.getX() - x;
        double dy = goal.getY() - y;
        double dz = goal.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean waterPassable(int x, int y, int z) {
        BlockState state = level.getBlockState(cursor.set(x, y, z));
        if (!state.getCollisionShape(level, cursor).isEmpty()) {
            return false;
        }
        return state.getBlock() == Blocks.WATER || !state.getFluidState().isEmpty();
    }

    private static List<BlockPos> finish(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.previous) {
            path.add(new BlockPos(n.x, n.y, n.z));
        }
        Collections.reverse(path);
        return path;
    }

    private List<BlockPos> simplify(List<BlockPos> path) {
        if (path.size() <= 2) {
            return path;
        }

        List<BlockPos> out = new ArrayList<>();
        out.add(path.get(0));
        BlockPos lastKept = path.get(0);
        int prevDx = step(path.get(1).getX() - path.get(0).getX());
        int prevDy = step(path.get(1).getY() - path.get(0).getY());
        int prevDz = step(path.get(1).getZ() - path.get(0).getZ());

        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos cur = path.get(i);
            BlockPos next = path.get(i + 1);
            int dx = step(next.getX() - cur.getX());
            int dy = step(next.getY() - cur.getY());
            int dz = step(next.getZ() - cur.getZ());
            boolean turn = dx != prevDx || dy != prevDy || dz != prevDz;
            boolean checkpoint = distanceSquared(lastKept, cur) >= STRAIGHT_CHECKPOINT_SPACING * STRAIGHT_CHECKPOINT_SPACING;
            boolean verticalBlend = cur.getY() != prev.getY() && cur.getY() != next.getY();

            if (turn || checkpoint || verticalBlend) {
                addIfDifferent(out, cur);
                lastKept = cur;
            }
            prevDx = dx;
            prevDy = dy;
            prevDz = dz;
        }

        BlockPos end = path.get(path.size() - 1);
        addIfDifferent(out, end);
        return stringPull(out);
    }

    private List<BlockPos> stringPull(List<BlockPos> path) {
        if (path.size() <= 2) {
            return path;
        }
        List<BlockPos> out = new ArrayList<>();
        out.add(path.get(0));
        int i = 0;
        while (i < path.size() - 1) {
            int next = i + 1;
            int limit = Math.min(path.size() - 1, i + MAX_SMOOTH_SKIP);
            for (int j = limit; j > i + 1; j--) {
                if (withinSmoothDist(path.get(i), path.get(j)) && clearWaterLine(path.get(i), path.get(j))) {
                    next = j;
                    break;
                }
            }
            addIfDifferent(out, path.get(next));
            i = next;
        }
        return out;
    }

    private boolean clearWaterLine(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps <= 1) {
            return waterPassable(b.getX(), b.getY(), b.getZ());
        }
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(a.getX() + 0.5 + dx * t);
            int y = (int) Math.floor(a.getY() + 0.5 + dy * t);
            int z = (int) Math.floor(a.getZ() + 0.5 + dz * t);
            if (!waterPassable(x, y, z) || waterClearance(x, y, z) < MIN_SMOOTH_CLEARANCE) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinSmoothDist(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();
        return dx * dx + dy * dy + dz * dz <= MAX_SMOOTH_DIST * MAX_SMOOTH_DIST;
    }

    private static void addIfDifferent(List<BlockPos> path, BlockPos pos) {
        if (path.isEmpty() || !path.get(path.size() - 1).equals(pos)) {
            path.add(pos);
        }
    }

    private static int step(int value) {
        return Integer.compare(value, 0);
    }

    private static int distanceSquared(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final long key;
        double cost = Double.MAX_VALUE;
        double combined = Double.MAX_VALUE;
        Node previous;
        int heapPosition = -1;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = BlockPos.asLong(x, y, z);
        }
    }

    private static final class OpenSet {
        private Node[] heap = new Node[512];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void insert(Node n) {
            if (size == heap.length) {
                Node[] next = new Node[heap.length * 2];
                System.arraycopy(heap, 0, next, 0, heap.length);
                heap = next;
            }
            heap[size] = n;
            n.heapPosition = size;
            siftUp(size++);
        }

        void update(Node n) {
            siftUp(n.heapPosition);
        }

        Node removeLowest() {
            Node out = heap[0];
            out.heapPosition = -1;
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                heap[0].heapPosition = 0;
                heap[size] = null;
                siftDown(0);
            } else {
                heap[0] = null;
            }
            return out;
        }

        private void siftUp(int i) {
            Node n = heap[i];
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (heap[p].combined <= n.combined) break;
                heap[i] = heap[p];
                heap[i].heapPosition = i;
                i = p;
            }
            heap[i] = n;
            n.heapPosition = i;
        }

        private void siftDown(int i) {
            int half = size >> 1;
            Node n = heap[i];
            while (i < half) {
                int c = (i << 1) + 1;
                int r = c + 1;
                if (r < size && heap[r].combined < heap[c].combined) c = r;
                if (heap[c].combined >= n.combined) break;
                heap[i] = heap[c];
                heap[i].heapPosition = i;
                i = c;
            }
            heap[i] = n;
            n.heapPosition = i;
        }
    }
}
