package com.booter.client.movement;

import com.booter.client.pathfinder.BaritonePathfinder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * Drives the player exclusively through vanilla key mappings: the same
 * pressed-state a physical key press sets. No packets, no teleportation, no
 * position writes — vanilla movement code does all the work.
 */
public final class MovementController {
    private boolean forwardHeld;
    private boolean backHeld;
    private boolean leftHeld;
    private boolean rightHeld;
    private boolean sprintHeld;
    private boolean crouchHeld;
    private boolean attackHeld;
    private boolean useHeld;
    private boolean jumpHeld;

    /**
     * Free directional movement: presses forward/back/strafe keys so the player can
     * move in any direction relative to where it's looking. Lets a caller (e.g. the
     * auto farmer) walk a fixed line while the head aims elsewhere. Attack is left to
     * {@link #setAttack}; crouch/auto-jump handled here.
     */
    public void move(Minecraft client, boolean forward, boolean back, boolean left, boolean right,
                     boolean crouch, boolean autoJump) {
        var options = client.options;
        hold(client, options.keyUp, forward, forwardHeld, v -> forwardHeld = v);
        hold(client, options.keyDown, back, backHeld, v -> backHeld = v);
        hold(client, options.keyLeft, left, leftHeld, v -> leftHeld = v);
        hold(client, options.keyRight, right, rightHeld, v -> rightHeld = v);
        hold(client, options.keySprint, false, sprintHeld, v -> sprintHeld = v);

        var player = client.player;
        if (autoJump && player != null && player.horizontalCollision && player.onGround()
                && canAscendHere(client)) {
            options.keyJump.setDown(true);
            jumpHeld = true;
        } else if (jumpHeld) {
            options.keyJump.setDown(isPhysicallyDown(client, options.keyJump));
            jumpHeld = false;
        }
        setCrouch(client, crouch);
    }

    /**
     * Swimming control: forward/sprint for horizontal motion, jump to rise and
     * sneak to sink. This still uses only vanilla key mappings, so Depth Strider,
     * Dolphin's Grace, Speed, server water physics, etc. apply naturally.
     */
    public void swim(Minecraft client, boolean forward, boolean sprint, boolean jump, boolean sink) {
        swim(client, forward, sprint, jump, sink, false, false);
    }

    /**
     * Swimming control with optional strafing for edge correction. All movement is
     * still vanilla key state: forward/sprint, jump, sneak, left and right.
     */
    public void swim(Minecraft client, boolean forward, boolean sprint, boolean jump, boolean sink,
                     boolean left, boolean right) {
        var options = client.options;
        hold(client, options.keyUp, forward, forwardHeld, v -> forwardHeld = v);
        hold(client, options.keyLeft, left, leftHeld, v -> leftHeld = v);
        hold(client, options.keyRight, right, rightHeld, v -> rightHeld = v);
        hold(client, options.keySprint, sprint && forward, sprintHeld, v -> sprintHeld = v);
        hold(client, options.keyJump, jump, jumpHeld, v -> jumpHeld = v);
        setCrouch(client, sink);
        setAttack(client, false);
        setUse(client, false);
    }

    private void hold(Minecraft client, KeyMapping key, boolean down, boolean wasHeld,
                      java.util.function.Consumer<Boolean> setHeld) {
        if (down) {
            key.setDown(true);
            setHeld.accept(true);
        } else if (wasHeld) {
            key.setDown(isPhysicallyDown(client, key));
            setHeld.accept(false);
        }
    }

    /** Called every client tick while a route is actively being walked. */
    public void tick(Minecraft client, boolean sprint, boolean crouch, boolean attack, boolean autoJump) {
        tick(client, true, sprint, crouch, attack, autoJump);
    }

    /**
     * Full control variant: {@code forward} lets a caller turn in place (no walking)
     * — useful for precise cornering, so the player aligns with the next node before
     * charging at it instead of walking diagonally into walls.
     */
    public void tick(Minecraft client, boolean forward, boolean sprint, boolean crouch, boolean attack, boolean autoJump) {
        var options = client.options;

        // Re-pressed every tick: opening a screen calls KeyMapping.releaseAll(),
        // re-asserting here keeps the route walking with the GUI open.
        if (forward) {
            options.keyUp.setDown(true);
            forwardHeld = true;
        } else if (forwardHeld) {
            options.keyUp.setDown(isPhysicallyDown(client, options.keyUp));
            forwardHeld = false;
        }

        // Sprinting only makes sense while moving forward.
        boolean doSprint = sprint && forward;

        // Auto-jump: when we walk into a block (horizontal collision) and are on
        // the ground, tap jump so 1-block steps are climbed automatically — but
        // only when a jump can actually get us up a level (clear headroom and a
        // standable step ahead). If we're boxed in / facing a wall taller than one
        // block, suppress the jump instead of bouncing uselessly in place. Once
        // clear, the jump key is restored to its real physical state.
        var player = client.player;
        if (forward && autoJump && player != null && player.horizontalCollision && player.onGround()
                && canAscendHere(client)) {
            options.keyJump.setDown(true);
            jumpHeld = true;
        } else if (jumpHeld) {
            options.keyJump.setDown(isPhysicallyDown(client, options.keyJump));
            jumpHeld = false;
        }

        if (doSprint) {
            options.keySprint.setDown(true);
            sprintHeld = true;
        } else if (sprintHeld) {
            options.keySprint.setDown(isPhysicallyDown(client, options.keySprint));
            sprintHeld = false;
        }

        setCrouch(client, crouch);
        setAttack(client, attack);
        setUse(client, false);
    }

    /** Holds or releases the sneak key on its own (no walking). */
    public void setCrouch(Minecraft client, boolean crouch) {
        var options = client.options;
        if (crouch) {
            options.keyShift.setDown(true);
            crouchHeld = true;
        } else if (crouchHeld) {
            options.keyShift.setDown(isPhysicallyDown(client, options.keyShift));
            crouchHeld = false;
        }
    }

    /**
     * Holds or releases the attack key on its own (no walking). Used by the
     * block miner, which mines in place. The initial press registers one click
     * exactly like a real mouse-down so the first swing/attack fires, then the
     * held down-state continues block breaking via vanilla's continueAttack().
     */
    public void setAttack(Minecraft client, boolean attack) {
        var options = client.options;
        if (attack) {
            if (!attackHeld) {
                InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(options.keyAttack);
                if (key != null && !key.equals(InputConstants.UNKNOWN)) {
                    KeyMapping.click(key);
                }
            }
            options.keyAttack.setDown(true);
            attackHeld = true;
        } else if (attackHeld) {
            options.keyAttack.setDown(isPhysicallyDown(client, options.keyAttack));
            attackHeld = false;
        }
    }

    /** Holds or releases the use/right-click key on its own (no walking). */
    public void setUse(Minecraft client, boolean use) {
        var options = client.options;
        if (use) {
            if (!useHeld) {
                InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(options.keyUse);
                if (key != null && !key.equals(InputConstants.UNKNOWN)) {
                    KeyMapping.click(key);
                }
            }
            options.keyUse.setDown(true);
            useHeld = true;
        } else if (useHeld) {
            options.keyUse.setDown(isPhysicallyDown(client, options.keyUse));
            useHeld = false;
        }
    }

    /** Releases every input we are holding, restoring real keyboard/mouse state. */
    public void releaseAll(Minecraft client) {
        var options = client.options;
        if (forwardHeld) {
            options.keyUp.setDown(isPhysicallyDown(client, options.keyUp));
            forwardHeld = false;
        }
        if (backHeld) {
            options.keyDown.setDown(isPhysicallyDown(client, options.keyDown));
            backHeld = false;
        }
        if (leftHeld) {
            options.keyLeft.setDown(isPhysicallyDown(client, options.keyLeft));
            leftHeld = false;
        }
        if (rightHeld) {
            options.keyRight.setDown(isPhysicallyDown(client, options.keyRight));
            rightHeld = false;
        }
        if (sprintHeld) {
            options.keySprint.setDown(isPhysicallyDown(client, options.keySprint));
            sprintHeld = false;
        }
        if (crouchHeld) {
            options.keyShift.setDown(isPhysicallyDown(client, options.keyShift));
            crouchHeld = false;
        }
        if (attackHeld) {
            options.keyAttack.setDown(isPhysicallyDown(client, options.keyAttack));
            attackHeld = false;
        }
        if (useHeld) {
            options.keyUse.setDown(isPhysicallyDown(client, options.keyUse));
            useHeld = false;
        }
        if (jumpHeld) {
            options.keyJump.setDown(isPhysicallyDown(client, options.keyJump));
            jumpHeld = false;
        }
    }

    /**
     * True if jumping from where the player stands could actually climb a level:
     * there must be clear space above the head to gain height AND a standable
     * 1-block step in the direction the player is facing. Prevents pointless jumps
     * when boxed in or facing a wall taller than one block.
     */
    private static boolean canAscendHere(Minecraft client) {
        var player = client.player;
        var level = client.level;
        if (player == null || level == null) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        // Clear headroom above the head (the block we'd rise into).
        BlockPos head2 = feet.above(2);
        if (!level.getBlockState(head2).getCollisionShape(level, head2).isEmpty()) {
            return false;
        }
        // Dominant horizontal facing — the direction we're walking / colliding into.
        double rad = Math.toRadians(player.getYRot());
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        int ox = 0;
        int oz = 0;
        if (Math.abs(fx) >= Math.abs(fz)) {
            ox = fx >= 0 ? 1 : -1;
        } else {
            oz = fz >= 0 ? 1 : -1;
        }
        // The spot one level up and ahead must be standable (a real step with room
        // to stand), otherwise rising a level here is impossible — don't jump.
        BlockPos step = new BlockPos(feet.getX() + ox, feet.getY() + 1, feet.getZ() + oz);
        // Only suppress the jump when the obstacle at FOOT level is itself an
        // auto-step block (a slab/stair on the ground, ≤0.6) — vanilla walks up those
        // without a jump. If the foot-level obstacle is a full block we must jump,
        // even if a slab sits on top of it (a ~1.5 lip): the jump clears the block and
        // step-assist handles the slab's half during the jump.
        BlockPos ahead = new BlockPos(feet.getX() + ox, feet.getY(), feet.getZ() + oz);
        if (BaritonePathfinder.isAutoStep(level, ahead)) {
            return false;
        }
        return BaritonePathfinder.isStandable(level, step);
    }

    private static boolean isPhysicallyDown(Minecraft client, KeyMapping binding) {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(binding);
        if (key == null || key.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        long window = client.getWindow().handle();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(client.getWindow(), key.getValue());
        }
        return false;
    }
}
