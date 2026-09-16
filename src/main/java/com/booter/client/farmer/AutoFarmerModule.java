package com.booter.client.farmer;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.rotation.RotationManager;
import com.booter.client.waypoint.Waypoint;
import com.booter.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto Farmer: continuously walks a saved waypoint route (default "plot4"),
 * looping, and harvests any fully-grown crop directly in front of it — breaking it
 * as it passes. Toggles for farm-vs-walk-only and for all crop types. Client-side;
 * vanilla movement/attack inputs and the shared smooth {@link RotationManager}.
 */
public final class AutoFarmerModule {
    public enum State {
        IDLE, FARMING
    }

    /** Max sideways distance (blocks) from the line-between-nodes a crop may be to
     *  count — 2 blocks either side of the player's path. */
    private static final double LINE_BAND = 2.0;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;

    private State state = State.IDLE;
    private int currentIndex;

    public AutoFarmerModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
    }

    public State getState() {
        return state;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void start(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        // Only one actor at a time.
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFisher().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);

        String routeName = config.settings.farmerRoute;
        int n = BooterClient.waypoints().loadRoute(routeName);
        if (n < 1) {
            BooterClient.chat("Auto Farmer: route '" + routeName + "' not found or empty.");
            return;
        }
        currentIndex = 0;
        state = State.FARMING;
        BooterClient.chat("Auto Farmer started on '" + routeName + "' (" + n + " waypoints).");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        movement.setAttack(client, false);
        movement.releaseAll(client);
        rotation.setActive(false);
        state = State.IDLE;
        BooterClient.chat("Auto Farmer stopped.");
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
        if (state != State.FARMING) {
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            stop(client);
            return;
        }
        WaypointManager wps = BooterClient.waypoints();
        if (wps.isEmpty()) {
            BooterClient.chat("Auto Farmer: route is empty — stopping.");
            stop(client);
            return;
        }
        if (currentIndex >= wps.size()) {
            currentIndex = 0;
        }

        Waypoint target = wps.get(currentIndex);
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // Reached the waypoint — advance (always loops).
        double radius = config.settings.waypointRadius;
        if (target.squaredDistanceTo(px, py, pz) <= radius * radius) {
            currentIndex = (currentIndex + 1) % wps.size();
            target = wps.get(currentIndex);
        }

        // The straight line the BODY follows: toward the current waypoint.
        double mdx = target.x - px;
        double mdz = target.z - pz;
        double mlen = Math.sqrt(mdx * mdx + mdz * mdz);
        float lineYaw = (float) Math.toDegrees(Math.atan2(-mdx, mdz));

        // The HEAD only: aim at a crop in front to break it, else face the line.
        boolean attack = false;
        float headYaw = lineYaw;
        float pitch = config.settings.customPitch;
        if (config.settings.farmerFarm) {
            BlockPos crop = nearestCropInFront(level, player, mdx, mdz, mlen);
            if (crop != null) {
                double cx = crop.getX() + 0.5;
                double cy = crop.getY() + 0.5;
                double cz = crop.getZ() + 0.5;
                double ddx = cx - px;
                double ddz = cz - pz;
                double horiz = Math.sqrt(ddx * ddx + ddz * ddz);
                headYaw = (float) Math.toDegrees(Math.atan2(-ddx, ddz));
                double dyEye = cy - player.getEyeY();
                pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dyEye, Math.max(0.001, horiz))), -90.0, 90.0);
                attack = client.hitResult instanceof BlockHitResult bhr
                        && client.hitResult.getType() == HitResult.Type.BLOCK
                        && bhr.getBlockPos().equals(crop);
            }
        }
        rotation.setTargetRotation(headYaw, pitch);
        rotation.setActive(true);
        movement.setAttack(client, attack);

        // Move the BODY along the line (toward the waypoint) regardless of where the
        // head is looking — computed as forward/strafe relative to the actual yaw, so
        // it strictly follows the line even while the head is turned to break a crop.
        float ay = player.getYRot();
        double ar = Math.toRadians(ay);
        double fwdX = -Math.sin(ar);
        double fwdZ = Math.cos(ar);
        double rightX = Math.cos(ar);
        double rightZ = Math.sin(ar);
        double ndx = mlen < 1.0e-6 ? 0.0 : mdx / mlen;
        double ndz = mlen < 1.0e-6 ? 0.0 : mdz / mlen;
        double fComp = ndx * fwdX + ndz * fwdZ;
        double sComp = ndx * rightX + ndz * rightZ;
        movement.move(client, fComp > 0.2, fComp < -0.2, sComp < -0.2, sComp > 0.2,
                config.settings.farmerCrouch, true);
    }

    /** Nearest fully-grown crop within reach and in front along the travel line. */
    private BlockPos nearestCropInFront(ClientLevel level, LocalPlayer player,
                                        double lineDx, double lineDz, double lineLen) {
        double range = config.settings.minerRange;
        double rangeSq = range * range;
        int r = (int) Math.ceil(range);
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double ndx = lineLen < 1.0e-6 ? 0.0 : lineDx / lineLen;
        double ndz = lineLen < 1.0e-6 ? 0.0 : lineDz / lineLen;
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (!isHarvestable(level.getBlockState(m))) {
                        continue;
                    }
                    double ddx = (m.getX() + 0.5) - px;
                    double ddy = (m.getY() + 0.5) - py;
                    double ddz = (m.getZ() + 0.5) - pz;
                    double dsq = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (dsq > rangeSq || dsq >= bestSq) {
                        continue;
                    }
                    // Must be on the line between nodes: in front, and within a narrow
                    // sideways band of the travel line (ignores adjacent rows).
                    double along = ddx * ndx + ddz * ndz;
                    double lateral = ddx * (-ndz) + ddz * ndx;
                    if (along < -0.2 || Math.abs(lateral) > LINE_BAND) {
                        continue;
                    }
                    // And in clear line of sight (a wall between us blocks it), so it
                    // never targets crops over walls or otherwise obstructed.
                    if (!inLineOfSight(level, player, m)) {
                        continue;
                    }
                    bestSq = dsq;
                    best = m.immutable();
                }
            }
        }
        return best;
    }

    /** True if the block is a fully-grown crop we should harvest. */
    private boolean isHarvestable(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof CropBlock crop) {
            return crop.isMaxAge(state); // wheat, carrots, potatoes, beetroot
        }
        if (config.settings.farmerAllCrops) {
            if (b instanceof NetherWartBlock) {
                return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
            }
            if (b instanceof CocoaBlock) {
                return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
            }
            if (b instanceof SweetBerryBushBlock) {
                return state.getValue(SweetBerryBushBlock.AGE) >= SweetBerryBushBlock.MAX_AGE;
            }
        }
        return false;
    }

    /** True if nothing solid is between the player's eye and the crop (crops have no
     *  collision, so the ray only stops on a wall — over-the-wall crops fail this). */
    private boolean inLineOfSight(ClientLevel level, LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(
                eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }
}
