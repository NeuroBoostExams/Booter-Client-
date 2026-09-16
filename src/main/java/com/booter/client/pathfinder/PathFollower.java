package com.booter.client.pathfinder;

import com.booter.client.movement.MovementController;
import com.booter.client.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.Collections;
import java.util.List;

/**
 * Reusable path follower using <b>pure pursuit</b>: each tick it finds a look-ahead
 * point a fixed distance ({@link #LOOKAHEAD} blocks) ahead along the path and steers
 * toward it, producing smooth, natural tracking without the node-to-node snapping of
 * a waypoint chaser. Shared by the standalone pathfinder and the waypoint walker so
 * both move identically.
 *
 * <p>It does not compute or recompute paths; the owner does that and calls
 * {@link #setPath} again. Each {@link #tick} returns a {@link Status} so the owner
 * can react (advance a waypoint on {@code ARRIVED}, recompute on {@code STUCK}).
 */
public final class PathFollower {
    public enum Status { IDLE, FOLLOWING, ARRIVED, STUCK }

    /** Horizontal distance to the final node that counts as arrived. */
    private static final double ARRIVE_DIST = 1.15;
    private static final double NODE_ADVANCE = 1.05;
    private static final double SPLINE_LOOK_T = 0.62;
    /** Only turn in place when the target is nearly behind us — continuous movement. */
    private static final float MOVE_YAW_LIMIT = 120.0f;
    /** Sprint whenever reasonably aligned with the look-ahead point. */
    private static final float SPRINT_YAW_LIMIT = 45.0f;
    /** Below this horizontal distance the heading is unstable (hold last yaw). */
    private static final double YAW_DEADZONE = 0.6;
    /** With jump boost, don't let tiny behind/under-foot node vectors drive yaw. */
    private static final double AIRBORNE_LOOKAHEAD_DIST = 2.75;
    /** Ticks of "trying to move forward but barely moving" before reporting STUCK. */
    private static final int STUCK_TICKS = 35;
    private static final double STUCK_MOVE_EPS = 0.02;

    private final MovementController movement;
    private final RotationManager rotation;

    private List<BlockPos> path = Collections.emptyList();
    private int segIndex;
    private float lastYaw;
    private int stuckTicks;
    private double lastPosX;
    private double lastPosZ;
    private boolean havePos;

    public PathFollower(MovementController movement, RotationManager rotation) {
        this.movement = movement;
        this.rotation = rotation;
    }

    /** Begins following {@code path}. */
    public void setPath(List<BlockPos> path, LocalPlayer player) {
        this.path = path == null ? Collections.emptyList() : path;
        this.segIndex = 0;
        this.stuckTicks = 0;
        this.havePos = false;
        this.lastYaw = player != null ? player.getYRot() : 0.0f;
    }

    public void clear() {
        path = Collections.emptyList();
        segIndex = 0;
    }

    public boolean isFollowing() {
        return path.size() >= 2;
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public int getIndex() {
        return Math.min(segIndex + 1, Math.max(0, path.size() - 1));
    }

    /**
     * Advances one tick using pure pursuit. {@code crouch}/{@code attack} pass
     * through to the movement controller; auto-jump is always on (self-suppressing
     * on stairs/slabs).
     */
    public Status tick(Minecraft client, boolean crouch, boolean attack) {
        return tick(client, crouch, attack, true);
    }

    public Status tick(Minecraft client, boolean crouch, boolean attack, boolean allowSprint) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || path.isEmpty()) {
            return Status.IDLE;
        }
        int n = path.size();
        if (n < 2) {
            return Status.ARRIVED;
        }
        double px = player.getX();
        double pz = player.getZ();

        boolean airborne = !player.onGround();
        BlockPos fin = path.get(n - 1);
        double fdx = (fin.getX() + 0.5) - px;
        double fdz = (fin.getZ() + 0.5) - pz;
        if (!airborne && fdx * fdx + fdz * fdz <= ARRIVE_DIST * ARRIVE_DIST
                && reachedY(fin, player)) {
            return Status.ARRIVED;
        }

        // Strict node progression: don't skip ahead just because the projection is
        // past a segment. The player must actually enter the next node center.
        while (!airborne && segIndex < n - 2
                && (reachedNode(path.get(segIndex + 1), player)
                || passedNode(path.get(segIndex), path.get(segIndex + 1), player))) {
            segIndex++;
        }

        BlockPos next = airborne ? airborneLookaheadNode(player) : groundLookaheadNode(player);
        double moveX = next.getX() + 0.5;
        double moveZ = next.getZ() + 0.5;
        double nextDx = moveX - px;
        double nextDz = moveZ - pz;
        boolean finalSegment = segIndex >= n - 2;
        boolean veryNearNext = nextDx * nextDx + nextDz * nextDz <= NODE_ADVANCE * NODE_ADVANCE && reachedY(next, player);
        if (!airborne && finalSegment && veryNearNext) {
            return Status.ARRIVED;
        }
        double[] look = stableSplineLookPoint(player, next);
        double dx = look[0] - px;
        double dz = look[2] - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw;
        if (airborne || horiz < YAW_DEADZONE) {
            targetYaw = lastYaw;
        } else {
            targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            lastYaw = targetYaw;
        }
        double dyEye = look[1] - player.getEyeY();
        float targetPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.5, horiz))), 0.0, 10.0);
        rotation.setTargetRotation(targetYaw, targetPitch);
        rotation.setActive(true);

        // Turn toward the look-ahead point before walking; sprint once well aligned.
        float moveYaw = (float) Math.toDegrees(Math.atan2(-nextDx, nextDz));
        double yawError = Math.abs(Mth.wrapDegrees((double) moveYaw - player.getYRot()));
        boolean forward = !(finalSegment && veryNearNext) && (airborne || yawError < MOVE_YAW_LIMIT);
        boolean sprint = allowSprint && !(finalSegment && veryNearNext) && (airborne || yawError < SPRINT_YAW_LIMIT);
        movement.tick(client, forward, sprint, crouch, attack, true);

        // Stuck: we wanted to move forward but barely moved for a while.
        if (havePos && forward) {
            double moved = (px - lastPosX) * (px - lastPosX) + (pz - lastPosZ) * (pz - lastPosZ);
            if (moved < STUCK_MOVE_EPS * STUCK_MOVE_EPS) {
                if (++stuckTicks > STUCK_TICKS) {
                    stuckTicks = 0;
                    return Status.STUCK;
                }
            } else {
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastPosX = px;
        lastPosZ = pz;
        havePos = true;

        return Status.FOLLOWING;
    }

    private BlockPos groundLookaheadNode(LocalPlayer player) {
        BlockPos direct = path.get(Math.min(segIndex + 1, path.size() - 1));
        double dx = direct.getX() + 0.5 - player.getX();
        double dz = direct.getZ() + 0.5 - player.getZ();
        if (dx * dx + dz * dz > 0.85 * 0.85 || segIndex >= path.size() - 2) {
            return direct;
        }
        return path.get(Math.min(segIndex + 2, path.size() - 1));
    }

    private BlockPos airborneLookaheadNode(LocalPlayer player) {
        BlockPos fallback = path.get(Math.min(segIndex + 1, path.size() - 1));
        double px = player.getX();
        double pz = player.getZ();
        for (int i = Math.min(segIndex + 1, path.size() - 1); i < path.size(); i++) {
            BlockPos candidate = path.get(i);
            double dx = candidate.getX() + 0.5 - px;
            double dz = candidate.getZ() + 0.5 - pz;
            if (dx * dx + dz * dz >= AIRBORNE_LOOKAHEAD_DIST * AIRBORNE_LOOKAHEAD_DIST) {
                return candidate;
            }
            fallback = candidate;
        }
        return fallback;
    }

    private boolean reachedNode(BlockPos node, LocalPlayer player) {
        double dx = node.getX() + 0.5 - player.getX();
        double dz = node.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz <= NODE_ADVANCE * NODE_ADVANCE
                && reachedYLoose(node, player);
    }

    private static boolean passedNode(BlockPos from, BlockPos node, LocalPlayer player) {
        double ax = from.getX() + 0.5;
        double az = from.getZ() + 0.5;
        double bx = node.getX() + 0.5;
        double bz = node.getZ() + 0.5;
        double sx = bx - ax;
        double sz = bz - az;
        double len2 = sx * sx + sz * sz;
        if (len2 < 1.0e-6) {
            return false;
        }
        double px = player.getX() - ax;
        double pz = player.getZ() - az;
        double progress = (px * sx + pz * sz) / len2;
        double dx = bx - player.getX();
        double dz = bz - player.getZ();
        return progress > 1.15 && dx * dx + dz * dz <= 4.0;
    }

    private static boolean reachedY(BlockPos node, LocalPlayer player) {
        double dy = player.getY() - node.getY();
        return dy >= -0.35 && dy <= 1.75;
    }

    private static boolean reachedYLoose(BlockPos node, LocalPlayer player) {
        double dy = player.getY() - node.getY();
        return dy >= -0.65 && dy <= 2.25;
    }

    private double[] stableSplineLookPoint(LocalPlayer player, BlockPos next) {
        double[] look = splineLookPoint(player);
        double directX = next.getX() + 0.5 - player.getX();
        double directZ = next.getZ() + 0.5 - player.getZ();
        double lookX = look[0] - player.getX();
        double lookZ = look[2] - player.getZ();
        double lookHorizSq = lookX * lookX + lookZ * lookZ;
        double dot = directX * lookX + directZ * lookZ;
        if (lookHorizSq < YAW_DEADZONE * YAW_DEADZONE || dot <= 0.0) {
            return new double[]{next.getX() + 0.5, next.getY() + 1.62, next.getZ() + 0.5};
        }
        float directYaw = (float) Math.toDegrees(Math.atan2(-directX, directZ));
        float lookYaw = (float) Math.toDegrees(Math.atan2(-lookX, lookZ));
        if (Math.abs(Mth.wrapDegrees((double) lookYaw - directYaw)) > 55.0) {
            return new double[]{next.getX() + 0.5, next.getY() + 1.62, next.getZ() + 0.5};
        }
        return look;
    }

    private double[] splineLookPoint(LocalPlayer player) {
        int n = path.size();
        BlockPos p0 = path.get(Math.max(0, segIndex - 1));
        BlockPos p1 = path.get(segIndex);
        BlockPos p2 = path.get(Math.min(n - 1, segIndex + 1));
        BlockPos p3 = path.get(Math.min(n - 1, segIndex + 2));
        double t = Math.max(SPLINE_LOOK_T, segmentProgress(player, p1, p2));
        return new double[]{
                catmull(p0.getX() + 0.5, p1.getX() + 0.5, p2.getX() + 0.5, p3.getX() + 0.5, t),
                catmull(p0.getY() + 1.62, p1.getY() + 1.62, p2.getY() + 1.62, p3.getY() + 1.62, t),
                catmull(p0.getZ() + 0.5, p1.getZ() + 0.5, p2.getZ() + 0.5, p3.getZ() + 0.5, t)
        };
    }

    private static double segmentProgress(LocalPlayer player, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5;
        double az = a.getZ() + 0.5;
        double dx = b.getX() + 0.5 - ax;
        double dz = b.getZ() + 0.5 - az;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1.0e-9) {
            return 1.0;
        }
        return Mth.clamp(((player.getX() - ax) * dx + (player.getZ() - az) * dz) / len2, 0.0, 1.0);
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }
}
