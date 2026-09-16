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
 * Pure-pursuit follower for 3D water paths. It looks five blocks ahead along the
 * path and uses vanilla swim inputs: forward/sprint plus jump to rise and sneak
 * to sink. Depth Strider, Speed and server-side water physics therefore apply
 * exactly as they do for a real player.
 */
public final class WaterPathFollower {
    public enum Status { IDLE, FOLLOWING, ARRIVED, STUCK }

    private static final double ARRIVE_DIST = 0.6;
    private static final double NODE_ADVANCE = 0.6;
    private static final double SPLINE_LOOK_T = 0.62;
    private static final double VERTICAL_INPUT_DEADZONE = 0.35;
    /** Inside this horizontal radius, vertical swim input should take over. */
    private static final double VERTICAL_ONLY_RADIUS = 0.85;
    private static final double DESCENT_COLUMN_RADIUS = 1.35;
    private static final double DESCENT_STAGE_DISTANCE = 1.55;
    private static final double ASCENT_COLUMN_RADIUS = 1.15;
    private static final double ASCENT_STAGE_DISTANCE = 1.35;
    private static final double STUCK_MOVE_EPS = 0.015;
    private static final int EDGE_ASSIST_STALL_TICKS = 8;
    private static final int EDGE_ASSIST_TICKS = 12;
    private static final int STUCK_TICKS = 34;

    private final MovementController movement;
    private final RotationManager rotation;

    private List<BlockPos> path = Collections.emptyList();
    private int segIndex;
    private float lastYaw;
    private int stuckTicks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean havePos;
    private int edgeAssistTicks;
    private int edgeAssistSide;

    public WaterPathFollower(MovementController movement, RotationManager rotation) {
        this.movement = movement;
        this.rotation = rotation;
    }

    public void setPath(List<BlockPos> path, LocalPlayer player) {
        this.path = path == null ? Collections.emptyList() : path;
        segIndex = 0;
        stuckTicks = 0;
        edgeAssistTicks = 0;
        edgeAssistSide = 0;
        havePos = false;
        lastYaw = player == null ? 0.0f : player.getYRot();
    }

    public void clear() {
        path = Collections.emptyList();
        segIndex = 0;
        havePos = false;
        edgeAssistTicks = 0;
        edgeAssistSide = 0;
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public int getIndex() {
        return Math.min(segIndex + 1, Math.max(0, path.size() - 1));
    }

    public Status tick(Minecraft client) {
        return tick(client, true);
    }

    public Status tick(Minecraft client, boolean allowSink) {
        LocalPlayer player = client.player;
        if (player == null || path.size() < 2) {
            return path.isEmpty() ? Status.IDLE : Status.ARRIVED;
        }

        double px = player.getX();
        double swimY = player.getEyeY() - 0.7;
        double pz = player.getZ();
        BlockPos fin = path.get(path.size() - 1);
        double fdx = fin.getX() + 0.5 - px;
        double fdy = fin.getY() + 0.5 - swimY;
        double fdz = fin.getZ() + 0.5 - pz;
        double finalHoriz = Math.sqrt(fdx * fdx + fdz * fdz);
        if (fdx * fdx + fdy * fdy + fdz * fdz <= ARRIVE_DIST * ARRIVE_DIST) {
            return Status.ARRIVED;
        }

        advancePastReached(px, swimY, pz);
        boolean finalColumn = finalHoriz < VERTICAL_ONLY_RADIUS && Math.abs(fdy) > VERTICAL_INPUT_DEADZONE;
        BlockPos nextNode = path.get(Math.min(segIndex + 1, path.size() - 1));
        double ndx = nextNode.getX() + 0.5 - px;
        double ndy = nextNode.getY() + 0.5 - swimY;
        double ndz = nextNode.getZ() + 0.5 - pz;
        double nextHoriz = Math.sqrt(ndx * ndx + ndz * ndz);
        boolean stageDescent = !finalColumn && ndy < -VERTICAL_INPUT_DEADZONE && nextHoriz > DESCENT_STAGE_DISTANCE;
        boolean descendColumn = !finalColumn && ndy < -VERTICAL_INPUT_DEADZONE && nextHoriz <= DESCENT_COLUMN_RADIUS;
        boolean stageAscent = !finalColumn && ndy > VERTICAL_INPUT_DEADZONE && nextHoriz > ASCENT_STAGE_DISTANCE;
        boolean ascendColumn = !finalColumn && ndy > VERTICAL_INPUT_DEADZONE && nextHoriz <= ASCENT_COLUMN_RADIUS;
        double[] target = finalColumn
                ? new double[]{fin.getX() + 0.5, fin.getY() + 0.5, fin.getZ() + 0.5}
                : descendColumn
                ? new double[]{nextNode.getX() + 0.5, nextNode.getY() + 0.5, nextNode.getZ() + 0.5}
                : ascendColumn
                ? visibleAscentTarget(nextNode)
                : stageDescent
                ? new double[]{nextNode.getX() + 0.5, player.getEyeY(), nextNode.getZ() + 0.5}
                : stageAscent
                ? visibleAscentTarget(nextNode)
                : stableSplineLookPoint(player, px, swimY, pz, nextNode);
        double dx = target[0] - px;
        double dz = target[2] - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dyEye = target[1] - player.getEyeY();
        boolean verticalColumn = finalColumn || descendColumn || ascendColumn
                || (nextHoriz < VERTICAL_ONLY_RADIUS && Math.abs(ndy) > VERTICAL_INPUT_DEADZONE);

        // If we're basically above/below the target, don't spin around trying to
        // face a numerically unstable horizontal vector. Keep the current yaw and
        // just use jump/sneak to move vertically, which is what a real player does.
        float yaw;
        if (finalColumn || descendColumn || nextHoriz < 0.2) {
            yaw = player.getYRot();
            lastYaw = yaw;
        } else {
            double yawDx = Math.abs(ndx) + Math.abs(ndz) > 0.001 ? ndx : dx;
            double yawDz = Math.abs(ndx) + Math.abs(ndz) > 0.001 ? ndz : dz;
            yaw = (float) Math.toDegrees(Math.atan2(-yawDx, yawDz));
            lastYaw = yaw;
        }

        float pitch = verticalColumn
                ? (dyEye > 0.0 ? -42.0f : 42.0f)
                : (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.35, horiz))), -65.0, 65.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);

        float moveYaw = (float) Math.toDegrees(Math.atan2(-ndx, ndz));
        double yawError = Math.abs(Mth.wrapDegrees((double) moveYaw - player.getYRot()));
        boolean forward = !verticalColumn && nextHoriz > 0.35 && yawError < 160.0;
        boolean sprint = false;
        boolean rise = !stageDescent && ndy > VERTICAL_INPUT_DEADZONE;
        boolean onSeaFloor = player.isInWater() && player.onGround();
        boolean sink = allowSink && !onSeaFloor && !stageDescent && ndy < -VERTICAL_INPUT_DEADZONE;

        boolean edgeAssist = forward && !verticalColumn && !stageDescent
                && (player.horizontalCollision || stuckTicks >= EDGE_ASSIST_STALL_TICKS);
        if (edgeAssist) {
            if (edgeAssistTicks <= 0) {
                edgeAssistSide = preferredStrafeSide(player.getYRot(), dx, dz);
                edgeAssistTicks = EDGE_ASSIST_TICKS;
            }
        } else if (edgeAssistTicks <= 0) {
            edgeAssistSide = 0;
        }
        boolean left = edgeAssistTicks > 0 && edgeAssistSide < 0;
        boolean right = edgeAssistTicks > 0 && edgeAssistSide > 0;
        if (edgeAssistTicks > 0) {
            edgeAssistTicks--;
        }
        boolean assistedForward = forward;
        movement.swim(client, assistedForward, sprint && assistedForward, rise, sink, left, right);

        if (havePos && (assistedForward || rise || sink)) {
            double moved = (px - lastX) * (px - lastX) + (swimY - lastY) * (swimY - lastY) + (pz - lastZ) * (pz - lastZ);
            if (moved < STUCK_MOVE_EPS * STUCK_MOVE_EPS) {
                if (++stuckTicks > STUCK_TICKS) {
                    stuckTicks = 0;
                    return Status.STUCK;
                }
            } else {
                stuckTicks = 0;
            }
        }
        lastX = px;
        lastY = swimY;
        lastZ = pz;
        havePos = true;
        return Status.FOLLOWING;
    }

    private void advancePastReached(double px, double py, double pz) {
        while (segIndex < path.size() - 2) {
            BlockPos next = path.get(segIndex + 1);
            double dx = next.getX() + 0.5 - px;
            double dy = next.getY() + 0.5 - py;
            double dz = next.getZ() + 0.5 - pz;
            boolean reached = dx * dx + dy * dy + dz * dz <= NODE_ADVANCE * NODE_ADVANCE;
            if (!reached) {
                break;
            }
            segIndex++;
        }
    }

    private double[] stableSplineLookPoint(LocalPlayer player, double px, double py, double pz, BlockPos next) {
        double[] look = splineLookPoint(px, py, pz);
        double directX = next.getX() + 0.5 - px;
        double directZ = next.getZ() + 0.5 - pz;
        double lookX = look[0] - px;
        double lookZ = look[2] - pz;
        double lookHorizSq = lookX * lookX + lookZ * lookZ;
        double dot = directX * lookX + directZ * lookZ;
        if (lookHorizSq < 0.2 * 0.2 || dot <= 0.0) {
            return new double[]{next.getX() + 0.5, next.getY() + 1.62, next.getZ() + 0.5};
        }
        float directYaw = (float) Math.toDegrees(Math.atan2(-directX, directZ));
        float lookYaw = (float) Math.toDegrees(Math.atan2(-lookX, lookZ));
        if (Math.abs(Mth.wrapDegrees((double) lookYaw - directYaw)) > 55.0) {
            return new double[]{next.getX() + 0.5, next.getY() + 1.62, next.getZ() + 0.5};
        }
        return look;
    }

    private double[] splineLookPoint(double px, double py, double pz) {
        int n = path.size();
        double t = segParam(segIndex, px, py, pz);
        t = Math.max(SPLINE_LOOK_T, t);
        BlockPos p0 = path.get(Math.max(0, segIndex - 1));
        BlockPos p1 = path.get(segIndex);
        BlockPos p2 = path.get(Math.min(n - 1, segIndex + 1));
        BlockPos p3 = path.get(Math.min(n - 1, segIndex + 2));
        return new double[]{
                catmull(p0.getX() + 0.5, p1.getX() + 0.5, p2.getX() + 0.5, p3.getX() + 0.5, t),
                catmull(p0.getY() + 1.62, p1.getY() + 1.62, p2.getY() + 1.62, p3.getY() + 1.62, t),
                catmull(p0.getZ() + 0.5, p1.getZ() + 0.5, p2.getZ() + 0.5, p3.getZ() + 0.5, t)
        };
    }

    private double segParam(int seg, double px, double py, double pz) {
        BlockPos a = path.get(seg);
        BlockPos b = path.get(seg + 1);
        double ax = a.getX() + 0.5;
        double ay = a.getY() + 0.5;
        double az = a.getZ() + 0.5;
        double dx = b.getX() + 0.5 - ax;
        double dy = b.getY() + 0.5 - ay;
        double dz = b.getZ() + 0.5 - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        if (len2 < 1.0e-9) {
            return 1.0;
        }
        return Mth.clamp(((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2, 0.0, 1.0);
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    private static double[] visibleAscentTarget(BlockPos node) {
        return new double[]{node.getX() + 0.5, node.getY() + 0.92, node.getZ() + 0.5};
    }

    private static int preferredStrafeSide(float yaw, double targetDx, double targetDz) {
        double rad = Math.toRadians(yaw);
        double rightX = -Math.cos(rad);
        double rightZ = -Math.sin(rad);
        double dot = targetDx * rightX + targetDz * rightZ;
        return dot >= 0.0 ? 1 : -1;
    }
}
