package com.booter.client.pathdebug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Draws the live A* search in the world: explored (red), open (yellow), final
 * path (green) and current target (blue) nodes as filled translucent cubes, plus
 * a thick connecting line through the final path and a destination marker.
 *
 * <p>Performance: everything is batched into the level renderer's shared buffers
 * (one {@code debugFilledBox} buffer for cubes, one {@code lines} buffer for the
 * line); nodes are distance-culled and decoded from packed longs without
 * allocating; nothing is created per node per frame. Filled cubes are translucent
 * (alpha = opacity) using the vanilla {@code position_color} debug pipeline, so no
 * deprecated APIs and no custom shaders.
 *
 * <p>Registered on {@code LevelRenderEvents.END_MAIN}.
 */
public final class PathDebugRenderer {
    // Base colours (RGB); per-spec opacities are applied as alpha.
    private static final int RGB_EXPLORED = 0xFF0000; // red
    private static final int RGB_OPEN = 0xFFFF00;     // yellow
    private static final int RGB_PATH = 0x00FF00;     // green
    private static final int RGB_TARGET = 0x2080FF;   // blue
    private static final float ALPHA_EXPLORED = 0.40f;
    private static final float ALPHA_OPEN = 0.50f;
    private static final float ALPHA_PATH = 0.75f;
    private static final float ALPHA_TARGET = 1.00f;
    private static final float LINE_WIDTH_CLAMP_MAX = 16.0f;
    private static final double NEAR_EPS = 0.1;

    /** Registered on LevelRenderEvents.END_MAIN. */
    public void render(LevelRenderContext context) {
        PathDebugManager mgr = PathDebugManager.get();
        PathDebugSettings s = mgr.settings;
        if (!s.enabled) {
            return;
        }
        MultiBufferSource.BufferSource consumers = context.bufferSource();
        PoseStack matrices = context.poseStack();
        if (consumers == null || matrices == null) {
            return;
        }

        long startNanos = System.nanoTime();
        Minecraft client = Minecraft.getInstance();
        Vec3 camPos = context.levelState().cameraRenderState.pos;
        Vector3fc forward = client.gameRenderer.getMainCamera().forwardVector();

        // At END_MAIN vanilla's main-pass PoseStack is identity with the camera at
        // the origin (it renders entities as worldPos − camPos). So we translate by
        // −camPos and then submit world coordinates; the camera rotation lives in
        // the global modelview, so the geometry stays put in the world as we move.
        matrices.pushPose();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = matrices.last();

        double maxSq = (double) s.maxRenderDistance * s.maxRenderDistance;
        float half = Math.max(0.02f, s.nodeSize);
        float op = Math.max(0.0f, Math.min(1.0f, s.nodeOpacity));

        VertexConsumer boxes = consumers.getBuffer(RenderTypes.debugFilledBox());

        // Explored (closed) nodes — red. Iterate the packed-long array by index.
        if (s.renderExploredNodes) {
            long[] closed = mgr.closedArray();
            int n = mgr.closedCount();
            int color = argb(RGB_EXPLORED, ALPHA_EXPLORED * op);
            for (int i = 0; i < n; i++) {
                long p = closed[i];
                drawNode(pose, boxes, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), half, color, camPos, maxSq);
            }
        }

        // Open (frontier) nodes — yellow.
        if (s.renderOpenNodes) {
            int color = argb(RGB_OPEN, ALPHA_OPEN * op);
            for (long p : mgr.openNodes()) {
                drawNode(pose, boxes, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), half, color, camPos, maxSq);
            }
        }

        // Final path nodes — green (slightly larger so they read over the others).
        List<BlockPos> path = mgr.finalPath();
        if (s.renderFinalPath && !path.isEmpty()) {
            int color = argb(RGB_PATH, ALPHA_PATH * op);
            float pathHalf = half * 1.25f;
            for (BlockPos node : path) {
                drawNode(pose, boxes, node.getX(), node.getY(), node.getZ(), pathHalf, color, camPos, maxSq);
            }
        }

        // Current target — blue, full opacity.
        BlockPos target = mgr.target();
        if (target != null) {
            drawNode(pose, boxes, target.getX(), target.getY(), target.getZ(), half * 1.4f,
                    argb(RGB_TARGET, ALPHA_TARGET * op), camPos, maxSq);
        }

        // Destination marker — a bigger box in the path colour.
        BlockPos dest = mgr.destination();
        if (s.renderDestination && dest != null) {
            drawNode(pose, boxes, dest.getX(), dest.getY(), dest.getZ(), half * 1.6f,
                    argb(s.lineColor & 0xFFFFFF, 0.85f * op), camPos, maxSq);
        }

        // Connecting line through the final path — thick, configurable colour.
        if (s.renderFinalPath && path.size() > 1) {
            VertexConsumer lines = consumers.getBuffer(RenderTypes.lines());
            float width = Math.max(1.0f, Math.min(LINE_WIDTH_CLAMP_MAX, s.lineThickness));
            int lineColor = 0xFF000000 | (s.lineColor & 0xFFFFFF);
            for (int i = 0; i + 1 < path.size(); i++) {
                BlockPos a = path.get(i);
                BlockPos b = path.get(i + 1);
                lineClipped(pose, lines, camPos, forward,
                        a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5,
                        b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5,
                        lineColor, width);
            }
        }

        matrices.popPose();
        // END_MAIN fires AFTER vanilla's final endBatch but while the world camera
        // transform is still active, so flush our own geometry now — otherwise it
        // lingers in the buffer and gets drawn later with the wrong (GUI) matrix,
        // which looks like angle-dependent drift / intermittent visibility.
        consumers.endBatch();
        mgr.recordRenderNanos(System.nanoTime() - startNanos);
    }

    /** Distance-culled filled cube centred in the block at (bx,by,bz). */
    private static void drawNode(PoseStack.Pose pose, VertexConsumer vc, int bx, int by, int bz,
                                 float half, int color, Vec3 camPos, double maxSq) {
        double cx = bx + 0.5;
        double cy = by + 0.5;
        double cz = bz + 0.5;
        double dx = cx - camPos.x;
        double dy = cy - camPos.y;
        double dz = cz - camPos.z;
        if (dx * dx + dy * dy + dz * dz > maxSq) {
            return;
        }
        fillBox(pose, vc, cx - half, cy - half, cz - half, cx + half, cy + half, cz + half, color);
    }

    /** Emits the 6 quad faces of an axis-aligned box (POSITION_COLOR, QUADS). */
    private static void fillBox(PoseStack.Pose pose, VertexConsumer c,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2, int color) {
        float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;
        float fx2 = (float) x2, fy2 = (float) y2, fz2 = (float) z2;
        // down (-Y)
        c.addVertex(pose, fx1, fy1, fz2).setColor(color);
        c.addVertex(pose, fx2, fy1, fz2).setColor(color);
        c.addVertex(pose, fx2, fy1, fz1).setColor(color);
        c.addVertex(pose, fx1, fy1, fz1).setColor(color);
        // up (+Y)
        c.addVertex(pose, fx1, fy2, fz1).setColor(color);
        c.addVertex(pose, fx2, fy2, fz1).setColor(color);
        c.addVertex(pose, fx2, fy2, fz2).setColor(color);
        c.addVertex(pose, fx1, fy2, fz2).setColor(color);
        // north (-Z)
        c.addVertex(pose, fx1, fy1, fz1).setColor(color);
        c.addVertex(pose, fx2, fy1, fz1).setColor(color);
        c.addVertex(pose, fx2, fy2, fz1).setColor(color);
        c.addVertex(pose, fx1, fy2, fz1).setColor(color);
        // south (+Z)
        c.addVertex(pose, fx2, fy1, fz2).setColor(color);
        c.addVertex(pose, fx1, fy1, fz2).setColor(color);
        c.addVertex(pose, fx1, fy2, fz2).setColor(color);
        c.addVertex(pose, fx2, fy2, fz2).setColor(color);
        // west (-X)
        c.addVertex(pose, fx1, fy1, fz2).setColor(color);
        c.addVertex(pose, fx1, fy1, fz1).setColor(color);
        c.addVertex(pose, fx1, fy2, fz1).setColor(color);
        c.addVertex(pose, fx1, fy2, fz2).setColor(color);
        // east (+X)
        c.addVertex(pose, fx2, fy1, fz1).setColor(color);
        c.addVertex(pose, fx2, fy1, fz2).setColor(color);
        c.addVertex(pose, fx2, fy2, fz2).setColor(color);
        c.addVertex(pose, fx2, fy2, fz1).setColor(color);
    }

    /** Draws a line clipped to the camera's near plane (avoids behind-camera garbage). */
    private static void lineClipped(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos, Vector3fc forward,
                                    double ax, double ay, double az, double bx, double by, double bz,
                                    int color, float width) {
        double fx = forward.x(), fy = forward.y(), fz = forward.z();
        double da = (ax - camPos.x) * fx + (ay - camPos.y) * fy + (az - camPos.z) * fz;
        double db = (bx - camPos.x) * fx + (by - camPos.y) * fy + (bz - camPos.z) * fz;
        if (da < NEAR_EPS && db < NEAR_EPS) {
            return;
        }
        if (da < NEAR_EPS) {
            double t = (NEAR_EPS - da) / (db - da);
            ax += (bx - ax) * t;
            ay += (by - ay) * t;
            az += (bz - az) * t;
        } else if (db < NEAR_EPS) {
            double t = (NEAR_EPS - db) / (da - db);
            bx += (ax - bx) * t;
            by += (ay - by) * t;
            bz += (az - bz) * t;
        }
        float nx = (float) (bx - ax);
        float ny = (float) (by - ay);
        float nz = (float) (bz - az);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-4f) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        lines.addVertex(pose, (float) ax, (float) ay, (float) az).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        lines.addVertex(pose, (float) bx, (float) by, (float) bz).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    private static int argb(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
