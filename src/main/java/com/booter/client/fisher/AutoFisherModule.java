package com.booter.client.fisher;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Auto fisher: casts the rod where you were aiming when you started, holds that
 * aim (rotating back if the view drifts), watches the bobber for a bite, reels the
 * fish in and recasts to the same spot — indefinitely.
 *
 * <p><b>Advanced Fisher</b>: optionally counts catches; once the count reaches the
 * configured target it looks straight down, performs one right-click, then looks
 * back up to the cast point and recasts (resetting the counter). Useful for a
 * periodic action below the player (depositing, using an item, …) every N fish.
 *
 * <p>Uses only the vanilla rod-use action and the shared smooth
 * {@link RotationManager}; client-side only.
 */
public final class AutoFisherModule {
    public enum State {
        IDLE, FISHING
    }

    /** Downward bobber velocity that signals a bite. */
    private static final double BITE_VELOCITY = -0.08;
    /** Ticks to let a fresh cast fly out and settle before watching for a bite. */
    private static final int SETTLE_TICKS = 20;
    /** Ticks to wait after casting / reeling before the next rod action. */
    private static final int CAST_COOLDOWN = 16;
    private static final int REEL_COOLDOWN = 12;
    private static final int ACTION_COOLDOWN = 10;
    /** Recast if no bite after this long (safety against a missed cast). */
    private static final int NO_BITE_TIMEOUT = 60 * 20;
    /** Cast only once the view is within this many degrees of the locked aim. */
    private static final float AIM_TOLERANCE = 3.0f;
    /** Straight down. */
    private static final float DOWN_PITCH = 90.0f;
    /** Horizontal displacement from the fishing spot that counts as knockback. */
    private static final double RETURN_THRESHOLD = 1.25;
    private static final double RETURN_Y_THRESHOLD = 1.5;
    /** Ticks to fish in place before retrying a return path that couldn't be found. */
    private static final int RETURN_RETRY_COOLDOWN = 40;
    private static final int PATH_MAX_NODES = 6000;
    private static final int PATH_MAX_RADIUS = 64;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower follower;

    private State state = State.IDLE;
    private float castYaw;
    private float castPitch;
    private int cooldown;
    private int ticksSinceCast;
    private int catchCount;
    // Advanced action (look down -> right-click -> back up) in progress.
    private boolean doingAction;
    private boolean actionClicked;
    // Knockback return: the spot we cast from, and whether we're walking back to it.
    private Vec3 homePos;
    private BlockPos homeBlock;
    private boolean returning;
    private int returnCooldown;

    public AutoFisherModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
        this.follower = new PathFollower(movement, rotation);
    }

    public State getState() {
        return state;
    }

    public int getCatchCount() {
        return catchCount;
    }

    public void start(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        if (rodHand(player) == null) {
            BooterClient.chat("Auto Fisher: hold a fishing rod first.");
            return;
        }
        // Only one actor at a time (fishing locks the view).
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.combat().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        // Lock onto wherever the player is currently aiming, and remember this spot.
        castYaw = player.getYRot();
        castPitch = player.getXRot();
        homePos = player.position();
        homeBlock = player.blockPosition();
        cooldown = 0;
        ticksSinceCast = 0;
        catchCount = 0;
        doingAction = false;
        actionClicked = false;
        returning = false;
        returnCooldown = 0;
        follower.clear();
        state = State.FISHING;
        BooterClient.chat(String.format("Auto Fisher started — casting toward yaw %.1f, pitch %.1f.", castYaw, castPitch));
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        movement.releaseAll(client);
        rotation.setActive(false);
        follower.clear();
        returning = false;
        state = State.IDLE;
        BooterClient.chat("Auto Fisher stopped.");
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
        if (state != State.FISHING) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            stop(client);
            return;
        }
        InteractionHand hand = rodHand(player);
        if (hand == null) {
            BooterClient.chat("Auto Fisher: no fishing rod in hand — stopping.");
            stop(client);
            return;
        }

        // Knockback recovery: if we've been pushed off our fishing spot, walk back.
        if (returning) {
            tickReturn(client, player);
            return;
        }
        if (returnCooldown > 0) {
            returnCooldown--;
        } else if (displacedFromHome(player)) {
            BooterClient.chat("Auto Fisher: knocked off spot — returning.");
            if (player.fishing != null) {
                client.gameMode.useItem(player, hand); // reel in before walking back
            }
            if (!beginReturn(client, player)) {
                // Can't path back right now — fish in place briefly, then retry.
                returnCooldown = RETURN_RETRY_COOLDOWN;
            }
            return;
        }

        // Advanced action sequence takes over (look down -> right-click -> resume).
        if (doingAction) {
            tickAction(client, player, hand);
            return;
        }

        // Always steer back to the locked cast aim, so the rod keeps landing in the
        // same spot even if the view drifts (or the mouse is bumped).
        rotation.setTargetRotation(castYaw, castPitch);
        rotation.setActive(true);

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        FishingHook bobber = player.fishing;
        if (bobber == null) {
            // No line out — cast, but only once we're actually facing the cast area.
            if (aimAligned(player)) {
                client.gameMode.useItem(player, hand);
                cooldown = CAST_COOLDOWN;
                ticksSinceCast = 0;
            }
            return;
        }

        // Line is out. Wait for it to settle, then watch for the bite.
        ticksSinceCast++;
        boolean bite = ticksSinceCast > SETTLE_TICKS && bobber.getDeltaMovement().y < BITE_VELOCITY;
        boolean timedOut = ticksSinceCast > NO_BITE_TIMEOUT;
        if (bite || timedOut) {
            client.gameMode.useItem(player, hand); // reel in (catches the fish)
            cooldown = REEL_COOLDOWN;
            if (bite) {
                catchCount++;
                BooterClient.overlay("Caught " + catchCount + (config.settings.fisherAdvanced
                        ? "/" + config.settings.fisherCatchTarget : ""));
                if (config.settings.fisherAdvanced && catchCount >= config.settings.fisherCatchTarget) {
                    doingAction = true;
                    actionClicked = false;
                }
            }
        }
    }

    /** Look straight down, right-click once, then hand back to normal fishing. */
    private void tickAction(Minecraft client, LocalPlayer player, InteractionHand hand) {
        // Keep the cast yaw, look straight down.
        rotation.setTargetRotation(castYaw, DOWN_PITCH);
        rotation.setActive(true);

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        // Wait until we're actually looking down before clicking.
        if (Math.abs(player.getXRot() - DOWN_PITCH) > AIM_TOLERANCE) {
            return;
        }
        if (!actionClicked) {
            // One right-click: interact with whatever's under the crosshair, else use the item.
            if (client.hitResult instanceof BlockHitResult bhr && client.hitResult.getType() == HitResult.Type.BLOCK) {
                client.gameMode.useItemOn(player, hand, bhr);
            } else {
                client.gameMode.useItem(player, hand);
            }
            actionClicked = true;
            cooldown = ACTION_COOLDOWN;
            return;
        }
        // Action done. If that right-click cast the rod downward, reel it back so we
        // resume from a clean state, then return to normal fishing (recasts up top).
        if (player.fishing != null) {
            client.gameMode.useItem(player, hand);
            cooldown = REEL_COOLDOWN;
        }
        catchCount = 0;
        doingAction = false;
        actionClicked = false;
        BooterClient.overlay("Advanced action done — fishing");
    }

    /** True if we've been pushed off the fishing spot (knockback). */
    private boolean displacedFromHome(LocalPlayer player) {
        if (homePos == null) {
            return false;
        }
        double dx = player.getX() - homePos.x;
        double dz = player.getZ() - homePos.z;
        return dx * dx + dz * dz > RETURN_THRESHOLD * RETURN_THRESHOLD
                || Math.abs(player.getY() - homePos.y) > RETURN_Y_THRESHOLD;
    }

    /** Computes a path back to the fishing spot and starts following it. */
    private boolean beginReturn(Minecraft client, LocalPlayer player) {
        Pathfinder finder = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, false);
        List<BlockPos> path = finder.findPath(client.level, player.blockPosition(), homeBlock);
        if (path == null || path.size() < 2) {
            return false;
        }
        follower.setPath(path, player);
        returning = true;
        return true;
    }

    /** Walks back to the fishing spot; on arrival resumes fishing (recasts up top). */
    private void tickReturn(Minecraft client, LocalPlayer player) {
        PathFollower.Status status = follower.tick(client, false, false);
        switch (status) {
            case ARRIVED -> {
                movement.releaseAll(client);
                follower.clear();
                returning = false;
                cooldown = 0; // resume: normal logic rotates to the cast aim and recasts
                BooterClient.overlay("Back on spot — fishing");
            }
            case STUCK -> {
                if (!beginReturn(client, player)) {
                    // Give up returning for a bit and fish from where we are.
                    movement.releaseAll(client);
                    follower.clear();
                    returning = false;
                    returnCooldown = RETURN_RETRY_COOLDOWN;
                }
            }
            case IDLE -> stop(client);
            default -> {
            }
        }
    }

    /** True once the view is within tolerance of the locked cast aim. */
    private boolean aimAligned(LocalPlayer player) {
        return Math.abs(Mth.wrapDegrees(player.getYRot() - castYaw)) <= AIM_TOLERANCE
                && Math.abs(player.getXRot() - castPitch) <= AIM_TOLERANCE;
    }

    /** The hand holding a fishing rod, or null if neither is. */
    private static InteractionHand rodHand(LocalPlayer player) {
        if (player.getMainHandItem().is(Items.FISHING_ROD)) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(Items.FISHING_ROD)) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }
}
