package com.booter.client.render;

import com.booter.client.BooterClient;
import com.booter.client.WaypointWalkerModule;
import com.booter.client.azalea.FloweringAzaleaFarmerModule;
import com.booter.client.combat.CombatModule;
import com.booter.client.config.ConfigManager;
import com.booter.client.coal.CoalMinerModule;
import com.booter.client.fishhunter.FishHunterModule;
import com.booter.client.miner.BlockMinerModule;
import com.booter.client.mushroom.MushroomFarmerModule;
import com.booter.client.navigation.NavigationEdge;
import com.booter.client.navigation.NavigationWaypoint;
import com.booter.client.pathfinder.PathfinderModule;
import com.booter.client.recorder.RecordedMovementNode;
import com.booter.client.turtlehunter.TurtleHunterModule;
import com.booter.client.waypoint.Waypoint;
import com.booter.client.waypoint.WaypointManager;
import com.booter.client.zealot.ZealotEmanFarmerModule;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Draws the route in-world: marker boxes, connecting lines, radius rings and
 * billboarded waypoint numbers. Everything goes through the world renderer's
 * shared line buffer (one draw call) and detail geometry is distance-culled,
 * so hundreds of waypoints stay cheap. No allocations per frame beyond what
 * the vanilla buffer system itself does.
 */
public final class RouteRenderer {
    private static final int RING_SEGMENTS = 24;
    private static final float[] RING_SIN = new float[RING_SEGMENTS + 1];
    private static final float[] RING_COS = new float[RING_SEGMENTS + 1];

    static {
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = (Math.PI * 2.0 * i) / RING_SEGMENTS;
            RING_SIN[i] = (float) Math.sin(angle);
            RING_COS[i] = (float) Math.cos(angle);
        }
    }

    /** Boxes/rings/labels are skipped beyond this distance from the camera. */
    private static final double DETAIL_CULL_SQ = 96.0 * 96.0;
    /** Packed lightmap coordinate for full brightness (LightTexture.FULL_BRIGHT). */
    private static final int FULL_BRIGHT = 0x00F0_00F0;
    /** Per-vertex line width (pixels) required by the 26.1 lines vertex format. */
    private static final float LINE_WIDTH = 4.0f;

    private static final int COLOR_PATH = 0xFF00E5FF;
    private static final int COLOR_LOOP = 0x9000E5FF;
    private static final int COLOR_MARKER = 0xFF00B8D4;
    private static final int COLOR_ACTIVE = 0xFF22FF55;
    private static final int COLOR_RING = 0x7800E5FF;
    private static final int COLOR_RING_ACTIVE = 0xB022FF55;

    // Pathfinder path — green.
    private static final int COLOR_PF_LINE = 0xFF2CFF6A;
    private static final int COLOR_PF_NODE = 0xC020D050;
    private static final int COLOR_PF_ACTIVE = 0xFF8CFFB0;

    // Mushroom Farmer target — orange.
    private static final int COLOR_MUSHROOM = 0xFFFFB000;
    // Block Miner target — yellow.
    private static final int COLOR_MINER = 0xFFFFE34D;
    // Coal Miner target — dark gray.
    private static final int COLOR_COAL = 0xFF4A4A4A;
    // Fish Hunter target — cyan/green.
    private static final int COLOR_FISH = 0xFF40F5C8;
    // Turtle Hunter target — sea green.
    private static final int COLOR_TURTLE = 0xFF65D46E;
    // Combat target — red.
    private static final int COLOR_COMBAT = 0xFFFF4D4D;
    // Flowering Azalea Farmer target — pink.
    private static final int COLOR_AZALEA = 0xFFFF77C8;
    // Zealot Eman Farmer target — purple.
    private static final int COLOR_ZEALOT = 0xFFB76BFF;
    private static final int COLOR_NAV_EDGE = 0x883FB950;
    private static final int COLOR_NAV_WAYPOINT = 0xCC3FB950;
    private static final int COLOR_NAV_INVALID = 0xAAFF4D4D;
    private static final int COLOR_REC_LINE = 0xCC40C4FF;
    private static final int COLOR_REC_NODE = 0xDD40C4FF;
    private static final int COLOR_REC_END = 0xFFFF5BAA;

    private final ConfigManager config;
    private final WaypointManager waypoints;
    private final WaypointWalkerModule walker;

    public RouteRenderer(ConfigManager config, WaypointManager waypoints, WaypointWalkerModule walker) {
        this.config = config;
        this.waypoints = waypoints;
        this.walker = walker;
    }

    /** Registered on LevelRenderEvents.END_MAIN. */
    public void render(LevelRenderContext context) {
        if (!config.settings.renderWaypoints) {
            return;
        }
        PathfinderModule pathfinder = BooterClient.pathfinder();
        MushroomFarmerModule farmer = BooterClient.mushroomFarmer();
        boolean hasWaypoints = !waypoints.isEmpty();
        boolean hasPath = pathfinder.getState() == PathfinderModule.State.FOLLOWING
                && pathfinder.getPath().size() > 1;
        boolean hasFarmer = farmer.getState() != MushroomFarmerModule.State.IDLE;
        BlockMinerModule blockMiner = BooterClient.blockMiner();
        boolean hasMiner = blockMiner.getState() != BlockMinerModule.State.IDLE;
        CoalMinerModule coalMiner = BooterClient.coalMiner();
        boolean hasCoal = coalMiner.getState() != CoalMinerModule.State.IDLE;
        FishHunterModule fishHunter = BooterClient.fishHunter();
        boolean hasFishHunter = fishHunter.getState() != FishHunterModule.State.IDLE;
        TurtleHunterModule turtleHunter = BooterClient.turtleHunter();
        boolean hasTurtleHunter = turtleHunter.getState() != TurtleHunterModule.State.IDLE;
        CombatModule combat = BooterClient.combat();
        boolean hasCombat = combat.getState() != CombatModule.State.IDLE;
        FloweringAzaleaFarmerModule azalea = BooterClient.azaleaFarmer();
        boolean hasAzalea = azalea.getState() != FloweringAzaleaFarmerModule.State.IDLE;
        ZealotEmanFarmerModule zealot = BooterClient.zealotEmanFarmer();
        boolean hasZealot = zealot.getState() != ZealotEmanFarmerModule.State.IDLE;
        boolean hasRecorderNodes = !BooterClient.movementRecorder().view().isEmpty();
        boolean hasNavigationDebug = config.settings.hierarchyDebug && BooterClient.navigation() != null && BooterClient.navigation().hasGraph();
        if (!hasWaypoints && !hasPath && !hasFarmer && !hasMiner && !hasCoal && !hasFishHunter && !hasTurtleHunter && !hasCombat && !hasAzalea && !hasZealot && !hasRecorderNodes && !hasNavigationDebug) {
            return;
        }

        MultiBufferSource.BufferSource consumers = context.bufferSource();
        PoseStack matrices = context.poseStack();
        if (consumers == null || matrices == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.getMainCamera();
        // The frame's camera position, used only for distance/front culling.
        Vec3 camPos = context.levelState().cameraRenderState.pos;

        // At END_MAIN vanilla's main-pass PoseStack is identity with the camera at
        // the origin (it draws entities as worldPos − camPos, keeping the stack
        // empty). So translate by −camPos and then submit WORLD coordinates; the
        // camera rotation is in the global modelview, so geometry stays anchored in
        // the world instead of drifting with the player.
        matrices.pushPose();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = matrices.last();

        List<Waypoint> list = waypoints.view();
        // Highlight the active waypoint of whichever module is walking the route.
        int activeIndex;
        if (blockMiner.isRouteActive()) {
            activeIndex = blockMiner.getRouteIndex();
        } else {
            activeIndex = walker.getState() == WaypointWalkerModule.State.STOPPED ? -1 : walker.getCurrentIndex();
        }
        float radius = config.settings.waypointRadius;

        VertexConsumer lines = consumers.getBuffer(RenderTypes.lines());

        // Connecting lines between consecutive waypoints, drawn slightly above
        // the recorded feet position.
        for (int i = 0; i + 1 < list.size(); i++) {
            Waypoint a = list.get(i);
            Waypoint b = list.get(i + 1);
            line(pose, lines, a.x, a.y + 0.1, a.z, b.x, b.y + 0.1, b.z, COLOR_PATH);
        }
        if (config.settings.loopRoute && list.size() > 2) {
            Waypoint last = list.get(list.size() - 1);
            Waypoint first = list.get(0);
            line(pose, lines, last.x, last.y + 0.1, last.z, first.x, first.y + 0.1, first.z, COLOR_LOOP);
        }

        // Full-block marker boxes (the whole block the waypoint sits in) + radius rings.
        for (int i = 0; i < list.size(); i++) {
            Waypoint wp = list.get(i);
            if (wp.squaredDistanceTo(camPos.x, camPos.y, camPos.z) > DETAIL_CULL_SQ) {
                continue;
            }
            boolean active = i == activeIndex;
            int boxColor = active ? COLOR_ACTIVE : COLOR_MARKER;
            double bx = Math.floor(wp.x);
            double by = Math.floor(wp.y);
            double bz = Math.floor(wp.z);
            box(pose, lines, bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0, boxColor);
            ring(pose, lines, wp, radius, active ? COLOR_RING_ACTIVE : COLOR_RING);
        }

        // Pathfinder path — green nodes joined by green lines.
        if (hasPath) {
            renderNodePath(pose, lines, camPos, camera.forwardVector(),
                    pathfinder.getPath(), pathfinder.getIndex());
        }

        if (hasNavigationDebug) {
            renderNavigationGraph(pose, lines, camPos, camera.forwardVector());
        }

        if (hasRecorderNodes) {
            renderRecordedMovement(pose, lines, camPos, camera.forwardVector());
        }

        // Waypoint Walker's A* path between waypoints — the strict line it follows.
        if (walker.getState() == WaypointWalkerModule.State.WALKING && walker.getPath().size() > 1) {
            renderNodePath(pose, lines, camPos, camera.forwardVector(),
                    walker.getPath(), walker.getPathIndex());
        }

        // Mushroom Farmer — green path to the target plus an orange target box.
        if (hasFarmer) {
            if (farmer.getState() == MushroomFarmerModule.State.WALKING && farmer.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        farmer.getPath(), farmer.getPathIndex());
            }
            targetBox(pose, lines, camPos, camera.forwardVector(), farmer.getTarget(), COLOR_MUSHROOM);
        }

        // Block Miner — green path plus a yellow target box.
        if (hasMiner) {
            if (blockMiner.getState() == BlockMinerModule.State.WALKING && blockMiner.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        blockMiner.getPath(), blockMiner.getPathIndex());
            }
            targetBox(pose, lines, camPos, camera.forwardVector(), blockMiner.getTarget(), COLOR_MINER);
        }

        // Coal Miner — green approach path plus a dark-gray target box on the coal.
        if (hasCoal) {
            if (coalMiner.getState() == CoalMinerModule.State.WALKING && coalMiner.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        coalMiner.getPath(), coalMiner.getPathIndex());
            }
            targetBox(pose, lines, camPos, camera.forwardVector(), coalMiner.getTarget(), COLOR_COAL);
        }

        // Fish Hunter — green path plus a cyan entity target box.
        if (hasFishHunter) {
            if ((fishHunter.getState() == FishHunterModule.State.PATHING_WATER
                    || fishHunter.getState() == FishHunterModule.State.PATHING_LAND)
                    && fishHunter.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        fishHunter.getPath(), fishHunter.getPathIndex());
            } else {
                entityLine(pose, lines, camPos, camera.forwardVector(), fishHunter.getTarget(), COLOR_PF_LINE);
            }
            entityBox(pose, lines, camPos, camera.forwardVector(), fishHunter.getTarget(), COLOR_FISH);
        }

        // Turtle Hunter — green path plus a sea-green entity target box.
        if (hasTurtleHunter) {
            if ((turtleHunter.getState() == TurtleHunterModule.State.PATHING_WATER
                    || turtleHunter.getState() == TurtleHunterModule.State.PATHING_LAND
                    || turtleHunter.getState() == TurtleHunterModule.State.RETURNING)
                    && turtleHunter.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        turtleHunter.getPath(), turtleHunter.getPathIndex());
            } else {
                entityLine(pose, lines, camPos, camera.forwardVector(), turtleHunter.getTarget(), COLOR_PF_LINE);
            }
            entityBox(pose, lines, camPos, camera.forwardVector(), turtleHunter.getTarget(), COLOR_TURTLE);
        }

        // Combat — green path plus a red entity target box.
        if (hasCombat) {
            if ((combat.getState() == CombatModule.State.PATHING_WATER
                    || combat.getState() == CombatModule.State.PATHING_LAND)
                    && combat.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        combat.getPath(), combat.getPathIndex());
            }
            entityBox(pose, lines, camPos, camera.forwardVector(), combat.getTarget(), COLOR_COMBAT);
        }

        // Flowering Azalea Farmer — green approach path plus a pink block target.
        if (hasAzalea) {
            if ((azalea.getState() == FloweringAzaleaFarmerModule.State.WALKING
                    || azalea.getState() == FloweringAzaleaFarmerModule.State.ROUTING)
                    && azalea.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        azalea.getPath(), azalea.getPathIndex());
            }
            targetBox(pose, lines, camPos, camera.forwardVector(), azalea.getTarget(), COLOR_AZALEA);
        }

        // Zealot Eman Farmer — green approach path plus a purple enderman target.
        if (hasZealot) {
            if (zealot.getState() == ZealotEmanFarmerModule.State.PATHING && zealot.getPath().size() > 1) {
                renderNodePath(pose, lines, camPos, camera.forwardVector(),
                        zealot.getPath(), zealot.getPathIndex());
            }
            entityBox(pose, lines, camPos, camera.forwardVector(), zealot.getTarget(), COLOR_ZEALOT);
        }

        matrices.popPose();

        // Billboarded waypoint numbers, sitting above the block (always above it).
        Font font = client.font;
        Quaternionf cameraRotation = camera.rotation();
        for (int i = 0; i < list.size(); i++) {
            Waypoint wp = list.get(i);
            if (wp.squaredDistanceTo(camPos.x, camPos.y, camPos.z) > DETAIL_CULL_SQ) {
                continue;
            }
            boolean active = i == activeIndex;
            double bx = Math.floor(wp.x) + 0.5;
            double by = Math.floor(wp.y) + 1.0;
            double bz = Math.floor(wp.z) + 0.5;
            matrices.pushPose();
            // Camera-relative (the base stack is identity with the camera at origin).
            matrices.translate(bx - camPos.x, by + (active ? 0.55 : 0.35) - camPos.y, bz - camPos.z);
            matrices.mulPose(cameraRotation);
            matrices.scale(0.025f, -0.025f, 0.025f);
            String label = Integer.toString(i + 1);
            float xOffset = -font.width(label) / 2.0f;
            font.drawInBatch(label, xOffset, 0.0f,
                    active ? COLOR_ACTIVE : 0xFFFFFFFF, false,
                    matrices.last().pose(), consumers,
                    Font.DisplayMode.SEE_THROUGH, 0x40000000, FULL_BRIGHT);
            matrices.popPose();
        }

        // END_MAIN fires after vanilla's final endBatch but while the world camera
        // transform is still active — flush our lines/boxes/labels now so they draw
        // with the correct matrix instead of lingering for a later (GUI) flush.
        consumers.endBatch();
    }

    private static final double NEAR_EPS = 0.1;

    private static void renderRecordedMovement(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos, Vector3fc forward) {
        List<RecordedMovementNode> nodes = BooterClient.movementRecorder().view();
        for (int i = 0; i + 1 < nodes.size(); i++) {
            RecordedMovementNode a = nodes.get(i);
            RecordedMovementNode b = nodes.get(i + 1);
            lineClipped(pose, lines, camPos, forward,
                    a.x, a.y + 0.12, a.z,
                    b.x, b.y + 0.12, b.z,
                    COLOR_REC_LINE);
        }
        for (RecordedMovementNode node : nodes) {
            double dx = node.x - camPos.x;
            double dy = (node.y + 0.5) - camPos.y;
            double dz = node.z - camPos.z;
            if (dx * dx + dy * dy + dz * dz > DETAIL_CULL_SQ || !inFront(node.x, node.y + 0.5, node.z, camPos, forward)) {
                continue;
            }
            boolean end = node.type == RecordedMovementNode.Type.END;
            double half = end ? 0.42 : 0.28;
            double height = end ? 0.9 : 0.48;
            solidBox(pose, lines,
                    node.x - half, node.y + 0.04, node.z - half,
                    node.x + half, node.y + 0.04 + height, node.z + half,
                    end ? COLOR_REC_END : COLOR_REC_NODE);
        }
    }

    private static void renderNavigationGraph(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos, Vector3fc forward) {
        var navigation = BooterClient.navigation();
        long now = System.currentTimeMillis();
        for (NavigationEdge edge : navigation.edges()) {
            NavigationWaypoint a = navigation.getWaypoint(edge.from);
            NavigationWaypoint b = navigation.getWaypoint(edge.to);
            if (a == null || b == null) {
                continue;
            }
            lineClipped(pose, lines, camPos, forward,
                    a.x + 0.5, a.y + 1.1, a.z + 0.5,
                    b.x + 0.5, b.y + 1.1, b.z + 0.5,
                    edge.isTemporarilyInvalid(now) ? COLOR_NAV_INVALID : COLOR_NAV_EDGE);
        }
        for (NavigationWaypoint waypoint : navigation.waypoints()) {
            double cx = waypoint.x + 0.5;
            double cy = waypoint.y + 0.9;
            double cz = waypoint.z + 0.5;
            double dx = cx - camPos.x;
            double dy = cy - camPos.y;
            double dz = cz - camPos.z;
            if (dx * dx + dy * dy + dz * dz > DETAIL_CULL_SQ || !inFront(cx, cy, cz, camPos, forward)) {
                continue;
            }
            solidBox(pose, lines, cx - 0.22, waypoint.y + 0.15, cz - 0.22,
                    cx + 0.22, waypoint.y + 0.75, cz + 0.22, COLOR_NAV_WAYPOINT);
        }
    }

    /**
     * Draws the A* path: a green polyline plus solid node markers. The line and
     * markers are anchored to the block grid (sitting just above each node's
     * floor) so they don't appear to float as you move. Segments are near-plane
     * clipped so even a 2-node straight path stays visible while walking it.
     */
    private static void renderNodePath(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos,
                                       Vector3fc forward, List<BlockPos> path, int active) {
        // Connecting polyline, drawn low (just off each node's floor) so it hugs
        // the ground instead of floating mid-block.
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);
            lineClipped(pose, lines, camPos, forward,
                    a.getX() + 0.5, a.getY() + 0.15, a.getZ() + 0.5,
                    b.getX() + 0.5, b.getY() + 0.15, b.getZ() + 0.5,
                    COLOR_PF_LINE);
        }

        // Solid node markers, anchored to the node's block.
        for (int i = 0; i < path.size(); i++) {
            BlockPos n = path.get(i);
            double cx = n.getX() + 0.5;
            double by = n.getY();
            double cz = n.getZ() + 0.5;
            double dx = cx - camPos.x;
            double dy = (by + 0.3) - camPos.y;
            double dz = cz - camPos.z;
            if (dx * dx + dy * dy + dz * dz > DETAIL_CULL_SQ) {
                continue;
            }
            if (!inFront(cx, by + 0.3, cz, camPos, forward)) {
                continue;
            }
            boolean isActive = i == active;
            int color = isActive ? COLOR_PF_ACTIVE : COLOR_PF_NODE;
            double half = isActive ? 0.34 : 0.26;
            double height = isActive ? 0.6 : 0.45;
            solidBox(pose, lines, cx - half, by + 0.05, cz - half, cx + half, by + 0.05 + height, cz + half, color);
        }
    }

    private static void entityLine(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos,
                                   Vector3fc forward, Entity entity, int color) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || entity == null || entity.isRemoved()) {
            return;
        }
        AABB bb = entity.getBoundingBox();
        double sx = client.player.getX();
        double sy = client.player.getY() + 0.15;
        double sz = client.player.getZ();
        double tx = (bb.minX + bb.maxX) * 0.5;
        double ty = bb.minY + 0.15;
        double tz = (bb.minZ + bb.maxZ) * 0.5;
        lineClipped(pose, lines, camPos, forward, sx, sy, sz, tx, ty, tz, color);
    }

    /**
     * Draws a "filled-looking" box using the line buffer: stacked horizontal
     * rings plus vertical edges. With a thick line width the slices merge into a
     * solid-looking marker (true face fill needs a custom textured pipeline).
     */
    private static void solidBox(PoseStack.Pose pose, VertexConsumer c,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2, int color) {
        int slices = 6;
        for (int s = 0; s <= slices; s++) {
            double yy = y1 + (y2 - y1) * s / slices;
            line(pose, c, x1, yy, z1, x2, yy, z1, color);
            line(pose, c, x2, yy, z1, x2, yy, z2, color);
            line(pose, c, x2, yy, z2, x1, yy, z2, color);
            line(pose, c, x1, yy, z2, x1, yy, z1, color);
        }
        line(pose, c, x1, y1, z1, x1, y2, z1, color);
        line(pose, c, x2, y1, z1, x2, y2, z1, color);
        line(pose, c, x2, y1, z2, x2, y2, z2, color);
        line(pose, c, x1, y1, z2, x1, y2, z2, color);
    }

    /** Draws a highlight box around a single target block (front- and distance-culled). */
    private static void targetBox(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos,
                                  Vector3fc forward, BlockPos t, int color) {
        if (t == null) {
            return;
        }
        double tx = t.getX() + 0.5, ty = t.getY() + 0.5, tz = t.getZ() + 0.5;
        double ddx = tx - camPos.x, ddy = ty - camPos.y, ddz = tz - camPos.z;
        if (ddx * ddx + ddy * ddy + ddz * ddz > DETAIL_CULL_SQ || !inFront(tx, ty, tz, camPos, forward)) {
            return;
        }
        box(pose, lines, t.getX() + 0.05, t.getY() + 0.05, t.getZ() + 0.05,
                t.getX() + 0.95, t.getY() + 0.95, t.getZ() + 0.95, color);
    }

    /** Draws a highlight box around a single target entity (front- and distance-cullled). */
    private static void entityBox(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos,
                                  Vector3fc forward, Entity entity, int color) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        AABB bb = entity.getBoundingBox();
        double tx = (bb.minX + bb.maxX) * 0.5;
        double ty = (bb.minY + bb.maxY) * 0.5;
        double tz = (bb.minZ + bb.maxZ) * 0.5;
        double ddx = tx - camPos.x, ddy = ty - camPos.y, ddz = tz - camPos.z;
        if (ddx * ddx + ddy * ddy + ddz * ddz > DETAIL_CULL_SQ || !inFront(tx, ty, tz, camPos, forward)) {
            return;
        }
        box(pose, lines, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, color);
    }

    /** True if the world point is in front of the camera (past the near plane). */
    private static boolean inFront(double wx, double wy, double wz, Vec3 camPos, Vector3fc forward) {
        double rx = wx - camPos.x;
        double ry = wy - camPos.y;
        double rz = wz - camPos.z;
        return rx * forward.x() + ry * forward.y() + rz * forward.z() > NEAR_EPS;
    }

    /**
     * Draws a line, clipping it to the camera's front half-space so a segment
     * with one endpoint behind the camera still shows its visible portion (and
     * a fully-behind segment is skipped) — avoids near-plane projection garbage.
     */
    private static void lineClipped(PoseStack.Pose pose, VertexConsumer lines, Vec3 camPos, Vector3fc forward,
                                    double ax, double ay, double az, double bx, double by, double bz, int color) {
        double fx = forward.x(), fy = forward.y(), fz = forward.z();
        double da = (ax - camPos.x) * fx + (ay - camPos.y) * fy + (az - camPos.z) * fz;
        double db = (bx - camPos.x) * fx + (by - camPos.y) * fy + (bz - camPos.z) * fz;
        if (da < NEAR_EPS && db < NEAR_EPS) {
            return; // both behind the camera
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
        line(pose, lines, ax, ay, az, bx, by, bz, color);
    }

    /** Draws the 12 edges of an axis-aligned box. */
    private static void box(PoseStack.Pose pose, VertexConsumer c,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2, int color) {
        // Bottom rectangle
        line(pose, c, x1, y1, z1, x2, y1, z1, color);
        line(pose, c, x2, y1, z1, x2, y1, z2, color);
        line(pose, c, x2, y1, z2, x1, y1, z2, color);
        line(pose, c, x1, y1, z2, x1, y1, z1, color);
        // Top rectangle
        line(pose, c, x1, y2, z1, x2, y2, z1, color);
        line(pose, c, x2, y2, z1, x2, y2, z2, color);
        line(pose, c, x2, y2, z2, x1, y2, z2, color);
        line(pose, c, x1, y2, z2, x1, y2, z1, color);
        // Vertical edges
        line(pose, c, x1, y1, z1, x1, y2, z1, color);
        line(pose, c, x2, y1, z1, x2, y2, z1, color);
        line(pose, c, x2, y1, z2, x2, y2, z2, color);
        line(pose, c, x1, y1, z2, x1, y2, z2, color);
    }

    private static void ring(PoseStack.Pose pose, VertexConsumer consumer, Waypoint wp, float radius, int color) {
        double y = wp.y + 0.05;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            line(pose, consumer,
                    wp.x + RING_COS[i] * radius, y, wp.z + RING_SIN[i] * radius,
                    wp.x + RING_COS[i + 1] * radius, y, wp.z + RING_SIN[i + 1] * radius,
                    color);
        }
    }

    private static void line(PoseStack.Pose pose, VertexConsumer consumer,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-4f) {
            return;
        }
        float nx = dx / length;
        float ny = dy / length;
        float nz = dz / length;
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
        consumer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
    }
}
