package com.booter.client.rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Smooth, human-like linear rotation shared by every module (pathfinder, block
 * miner, waypoint walker, mushroom farmer). Each client tick it eases the
 * player's view a configurable fraction of the remaining angle toward the
 * target, always taking the shortest turn and never overshooting.
 *
 * <p>It is driven <b>every rendered frame</b> for maximum smoothness (it moves at
 * the monitor's refresh rate, e.g. 144 Hz, not just 20 Hz), but the turn <i>rate</i>
 * is scaled by real frame delta-time so it is identical at 30 FPS or 240 FPS and
 * stays stable through FPS drops. It writes the player's own yaw/pitch directly —
 * client-side only, no packets, no teleport.
 *
 * <p>Algorithm each frame (with {@code m} = rotation multiplier in 0.01..1.0 and
 * {@code dt} = seconds since the last frame):
 * <pre>
 *   yawDiff   = wrapDegrees(targetYaw - currentYaw)   // shortest signed turn
 *   pitchDiff = targetPitch - currentPitch
 *   // per-frame fraction equivalent to applying m once per tick (20/s):
 *   factor    = 1 - (1 - m) ^ (dt * 20)
 *   currentYaw   += yawDiff   * factor
 *   currentPitch += pitchDiff * factor                // clamped to [-90, 90]
 * </pre>
 * Because each step is a fraction (&le; 1) of what's left, it cannot overshoot,
 * and the exponential ease-out makes turns glide to a stop. Once both axes are
 * within {@link #DONE_THRESHOLD} it snaps exactly and stops, so it never jitters
 * in place.
 */
public final class RotationManager {
    /** Stop stepping (snap to target) once both axes are within this many degrees. */
    private static final float DONE_THRESHOLD = 0.5f;
    /** Re-roll the human variance only when the target jumps more than this (deg). */
    private static final float CHANGE_THRESHOLD = 4.0f;
    /** Optional per-target-change randomization, in degrees. */
    private static final float YAW_VARIANCE = 0.2f;
    private static final float PITCH_VARIANCE = 0.1f;
    private static final float MIN_MULTIPLIER = 0.01f;
    private static final float MAX_MULTIPLIER = 1.0f;

    private final Random random = new Random();

    private boolean active;
    private boolean hasTarget;
    private float targetYaw;
    private float targetPitch;

    private float currentYaw;
    private float currentPitch;
    private boolean rotating;

    // Small constant offset applied to the target, re-rolled only when the target
    // actually changes — so the resting aim is never perfectly, robotically exact.
    private float yawVariance;
    private float pitchVariance;

    private boolean randomize;
    private float multiplier = 0.25f;
    private long lastNanos;

    // ------------------------------------------------------------------ configuration

    /** Rotation speed, 0.01 (slow/smooth) .. 1.0 (instant). Clamped. */
    public void setMultiplier(float multiplier) {
        this.multiplier = Mth.clamp(multiplier, MIN_MULTIPLIER, MAX_MULTIPLIER);
    }

    /** Enables the small ±variance applied when the look target changes. */
    public void setRandomize(boolean randomize) {
        this.randomize = randomize;
    }

    public boolean isActive() {
        return active;
    }

    /** Turns steering on/off. While off, {@link #update()} leaves the view alone. */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            rotating = false;
            hasTarget = false;
            yawVariance = 0.0f;
            pitchVariance = 0.0f;
            lastNanos = 0L;
        }
    }

    // ------------------------------------------------------------------ spec API

    /**
     * Sets the desired view angles (toward a path node, a block being mined, a
     * waypoint or any look-at target). The tiny human variance is re-rolled only
     * when the target genuinely changes, not on the small per-tick tracking drift.
     */
    public void setTargetRotation(float yaw, float pitch) {
        float newYaw = Mth.wrapDegrees(yaw);
        float newPitch = Mth.clamp(pitch, -90.0f, 90.0f);

        boolean changed = !hasTarget
                || Math.abs(Mth.degreesDifference(targetYaw, newYaw)) > CHANGE_THRESHOLD
                || Math.abs(targetPitch - newPitch) > CHANGE_THRESHOLD;
        if (changed) {
            if (randomize) {
                yawVariance = (random.nextFloat() * 2.0f - 1.0f) * YAW_VARIANCE;
                pitchVariance = (random.nextFloat() * 2.0f - 1.0f) * PITCH_VARIANCE;
            } else {
                yawVariance = 0.0f;
                pitchVariance = 0.0f;
            }
        }

        targetYaw = newYaw;
        targetPitch = newPitch;
        hasTarget = true;
    }

    /**
     * Eases the view toward the target by one frame's worth of smoothing. Call once
     * per rendered frame (after the modules have set their target) — never pauses
     * movement, it only steers the view.
     */
    public void update() {
        if (!active || !hasTarget) {
            lastNanos = 0L;
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            lastNanos = 0L;
            return;
        }

        // Real frame delta (seconds). First frame after activation assumes one tick;
        // long hitches (lag spike, pause) are clamped so they can't cause a snap turn.
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? (1.0f / 20.0f) : (now - lastNanos) / 1_000_000_000.0f;
        lastNanos = now;
        dt = Math.min(dt, 0.1f);

        // Re-base on the player's live rotation each frame so we resume smoothly from
        // wherever the view actually is (e.g. after the user nudged the mouse) and
        // never snap from a stale internal value.
        currentYaw = player.getYRot();
        currentPitch = player.getXRot();

        float goalYaw = targetYaw + yawVariance;
        float goalPitch = Mth.clamp(targetPitch + pitchVariance, -90.0f, 90.0f);

        // Shortest signed differences (yaw wraps to [-180, 180]).
        float yawDiff = Mth.degreesDifference(currentYaw, goalYaw);
        float pitchDiff = goalPitch - currentPitch;

        if (Math.abs(yawDiff) <= DONE_THRESHOLD && Math.abs(pitchDiff) <= DONE_THRESHOLD) {
            // Close enough: snap exactly and stop, so the aim doesn't jitter.
            currentYaw = goalYaw;
            currentPitch = goalPitch;
            rotating = false;
        } else {
            // FPS-independent exponential ease: the per-frame fraction is set so that
            // applying it across one tick's worth of frames equals one `multiplier`
            // step. A fraction (<= 1) of what's left, so it can never overshoot.
            float factor = (float) (1.0 - Math.pow(1.0 - multiplier, dt * 20.0));
            currentYaw += yawDiff * factor;
            currentPitch = Mth.clamp(currentPitch + pitchDiff * factor, -90.0f, 90.0f);
            rotating = true;
        }

        player.setYRot(Mth.wrapDegrees(currentYaw));
        player.setXRot(currentPitch);
    }

    /** True while actively turning toward the target (not yet within threshold). */
    public boolean isRotating() {
        return rotating;
    }

    public float getCurrentYaw() {
        return currentYaw;
    }

    public float getCurrentPitch() {
        return currentPitch;
    }
}
