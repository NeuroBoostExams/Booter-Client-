package com.booter.client.combat;

import com.booter.client.pathfinder.Pathfinder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.booter.client.movement.MovementController;
import com.booter.client.pathfinder.BaritonePathfinder;
import com.booter.client.pathfinder.PathFollower;
import com.booter.client.pathfinder.WaterPathfinder;
import com.booter.client.pathfinder.WaterPathFollower;
import com.booter.client.rotation.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * General mob combat module. It scans for enabled mob types in a fixed priority
 * order, paths to the nearest matching living target, aims smoothly, and holds
 * the normal attack key. One-tap mode drops the target immediately after the
 * first aligned swing so the next configured mob type can be selected.
 */
public final class CombatModule {
    public enum State {
        IDLE, SCANNING, PATHING_WATER, PATHING_LAND, ATTACKING
    }

    private static final int SCAN_INTERVAL = 1;
    private static final int TARGET_REFRESH_INTERVAL = 1;
    private static final int REPATH_INTERVAL = 20;
    private static final int ONE_TAP_SKIP_TICKS = 30;
    private static final int PATH_MAX_NODES = 12000;
    private static final int PATH_MAX_RADIUS = 224;
    private static final double SCAN_RADIUS = 48.0;
    private static final double ATTACK_RANGE = 4.0;
    private static final double PRE_AIM_RANGE = 8.0;
    private static final double APPROACH_DISTANCE = 2.4;
    private static final float AIM_TOLERANCE = 5.0f;

    private final ConfigManager config;
    private final MovementController movement;
    private final RotationManager rotation;
    private final PathFollower landFollower;
    private final WaterPathFollower waterFollower;

    private State state = State.IDLE;
    private Entity target;
    private int scanCooldown;
    private int targetRefreshCooldown;
    private int repathCooldown;
    private int oneTapSkippedId = -1;
    private int oneTapSkipTicks;

    public CombatModule(ConfigManager config, MovementController movement, RotationManager rotation) {
        this.config = config;
        this.movement = movement;
        this.rotation = rotation;
        this.landFollower = new PathFollower(movement, rotation);
        this.waterFollower = new WaterPathFollower(movement, rotation);
    }

    public State getState() {
        return state;
    }

    public Entity getTarget() {
        return target;
    }

    public List<BlockPos> getPath() {
        return state == State.PATHING_WATER ? waterFollower.getPath() : landFollower.getPath();
    }

    public int getPathIndex() {
        return state == State.PATHING_WATER ? waterFollower.getIndex() : landFollower.getIndex();
    }

    public void start(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        if (!anyEnabled()) {
            BooterClient.chat("Combat: enable at least one mob type.");
            return;
        }
        BooterClient.walker().stopRoute(client);
        BooterClient.pathfinder().stop(client);
        BooterClient.mushroomFarmer().stop(client);
        BooterClient.blockMiner().stop(client);
        BooterClient.autoFisher().stop(client);
        BooterClient.fishHunter().stop(client);
        BooterClient.turtleHunter().stop(client);
        BooterClient.azaleaFarmer().stop(client);
        BooterClient.coalMiner().stop(client);
        BooterClient.autoFarmer().stop(client);

        target = null;
        scanCooldown = 0;
        targetRefreshCooldown = 0;
        repathCooldown = 0;
        oneTapSkippedId = -1;
        oneTapSkipTicks = 0;
        landFollower.clear();
        waterFollower.clear();
        state = State.SCANNING;
        BooterClient.chat("Combat started.");
    }

    public void stop(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        movement.releaseAll(client);
        rotation.setActive(false);
        landFollower.clear();
        waterFollower.clear();
        target = null;
        state = State.IDLE;
        BooterClient.chat("Combat stopped.");
    }

    public void toggle(Minecraft client) {
        if (state == State.IDLE) {
            start(client);
        } else {
            stop(client);
        }
    }

    public void tick(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            stop(client);
            return;
        }
        if (oneTapSkipTicks > 0 && --oneTapSkipTicks == 0) {
            oneTapSkippedId = -1;
        }

        if (!validTarget(target)) {
            target = null;
            movement.setAttack(client, false);
            movement.setUse(client, false);
            landFollower.clear();
            waterFollower.clear();
            state = State.SCANNING;
            scanCooldown = 0;
        }

        if (target == null) {
            if (scanCooldown-- <= 0) {
                scanCooldown = SCAN_INTERVAL;
                target = findTarget(client, player);
                if (target != null) {
                    targetRefreshCooldown = TARGET_REFRESH_INTERVAL;
                    beginPath(client, player);
                }
            }
            return;
        }

        if (targetRefreshCooldown-- <= 0) {
            targetRefreshCooldown = TARGET_REFRESH_INTERVAL;
            Entity refreshed = findTarget(client, player);
            if (refreshed != null && refreshed.getId() != target.getId()
                    && shouldSwitchTarget(player, target, refreshed)) {
                target = refreshed;
                repathCooldown = 0;
                beginPath(client, player);
                return;
            }
        }

        if (canAttack(player, target)) {
            attackTarget(client, player);
            return;
        }
        if (inAttackRange(player, target)) {
            movement.setAttack(client, false);
            repathCooldown = REPATH_INTERVAL;
            beginPath(client, player);
            return;
        }

        movement.setAttack(client, false);
        if (state == State.ATTACKING || repathCooldown-- <= 0) {
            repathCooldown = REPATH_INTERVAL;
            beginPath(client, player);
            return;
        }

        if (state == State.PATHING_WATER) {
            WaterPathFollower.Status status = waterFollower.tick(client);
            if (status == WaterPathFollower.Status.ARRIVED || status == WaterPathFollower.Status.STUCK || status == WaterPathFollower.Status.IDLE) {
                beginPath(client, player);
            }
        } else if (state == State.PATHING_LAND) {
            PathFollower.Status status = landFollower.tick(client, false, false);
            if (status == PathFollower.Status.ARRIVED || status == PathFollower.Status.STUCK || status == PathFollower.Status.IDLE) {
                beginPath(client, player);
            }
        } else {
            beginPath(client, player);
        }
        if (canAttack(player, target)) {
            attackTarget(client, player);
            return;
        }
        if (shouldAimWhilePathing(player)) {
            aimAtTarget(player);
        }
    }

    private Entity findTarget(Minecraft client, LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
        List<Entity> nearby = client.level.getEntities(player, box, this::validTarget);
        for (TargetKind kind : TargetKind.ORDER) {
            if (!enabled(kind)) {
                continue;
            }
            Entity found = nearby.stream()
                    .filter(e -> matches(kind, e))
                    .min(Comparator.comparingDouble(player::distanceToSqr))
                    .orElse(null);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean shouldSwitchTarget(LocalPlayer player, Entity current, Entity candidate) {
        if (state == State.ATTACKING && inAttackRange(player, current)) {
            return false;
        }
        int currentPriority = priorityOf(current);
        int candidatePriority = priorityOf(candidate);
        if (candidatePriority < currentPriority) {
            return true;
        }
        if (candidatePriority > currentPriority) {
            return false;
        }
        return player.distanceToSqr(candidate) + 4.0 < player.distanceToSqr(current);
    }

    private int priorityOf(Entity entity) {
        for (int i = 0; i < TargetKind.ORDER.length; i++) {
            TargetKind kind = TargetKind.ORDER[i];
            if (enabled(kind) && matches(kind, entity)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void beginPath(Minecraft client, LocalPlayer player) {
        if (!validTarget(target)) {
            target = null;
            state = State.SCANNING;
            return;
        }
        List<BlockPos> goals = visibleApproachGoals(client, target);
        BlockPos pos = goals.isEmpty() ? approachPos(player, target) : goals.get(0);

        WaterPathfinder water = new WaterPathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS);
        BlockPos swimStart = BlockPos.containing(player.getX(), player.getEyeY() - 0.7, player.getZ());
        List<BlockPos> waterPath = water.findPath(client.level, swimStart, pos);
        if (waterPath != null && waterPath.size() >= 2) {
            landFollower.clear();
            waterFollower.setPath(waterPath, player);
            state = State.PATHING_WATER;
            return;
        }

        Pathfinder land = new Pathfinder(PATH_MAX_NODES, PATH_MAX_RADIUS, true);
        List<BlockPos> landPath = goals.isEmpty()
                ? land.findPath(client.level, player.blockPosition(), pos)
                : land.findPath(client.level, player.blockPosition(), goals);
        if (landPath != null && landPath.size() >= 2) {
            waterFollower.clear();
            landFollower.setPath(landPath, player);
            state = State.PATHING_LAND;
            return;
        }
        state = State.SCANNING;
        scanCooldown = SCAN_INTERVAL;
    }

    private void attackTarget(Minecraft client, LocalPlayer player) {
        landFollower.clear();
        waterFollower.clear();
        state = State.ATTACKING;
        double dx = target.getX() - player.getX();
        double dy = target.getBoundingBox().getCenter().y - player.getEyeY();
        double dz = target.getZ() - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean forward = horiz > 2.1;
        boolean rise = dy > 0.45;
        boolean sink = dy < -0.45;
        if (player.isInWater()) {
            movement.swim(client, forward, false, rise, sink);
        } else {
            movement.tick(client, forward, false, false, false, true);
        }
        aimAtTarget(player);
        movement.setUse(client, false);
        movement.setAttack(client, true);
        if (config.settings.combatOneTap) {
            oneTapSkippedId = target.getId();
            oneTapSkipTicks = ONE_TAP_SKIP_TICKS;
            target = null;
            movement.setAttack(client, false);
            state = State.SCANNING;
            scanCooldown = 0;
        }
    }

    private void aimAtTarget(LocalPlayer player) {
        double tx = target.getX();
        double ty = target.getBoundingBox().getCenter().y;
        double tz = target.getZ();
        double dx = tx - player.getX();
        double dy = ty - player.getEyeY();
        double dz = tz - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(0.25, horiz))), -80.0, 80.0);
        rotation.setTargetRotation(yaw, pitch);
        rotation.setActive(true);
    }

    private static BlockPos approachPos(LocalPlayer player, Entity mob) {
        double dx = player.getX() - mob.getX();
        double dz = player.getZ() - mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            float yaw = player.getYRot();
            double rad = Math.toRadians(yaw);
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
            len = 1.0;
        }
        double scale = APPROACH_DISTANCE / len;
        return BlockPos.containing(mob.getX() + dx * scale, mob.getY(), mob.getZ() + dz * scale);
    }

    private static double horizontalDistanceSqr(LocalPlayer player, Entity entity) {
        double dx = player.getX() - entity.getX();
        double dz = player.getZ() - entity.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean inAttackRange(LocalPlayer player, Entity entity) {
        return entity != null && player.distanceToSqr(entity) <= ATTACK_RANGE * ATTACK_RANGE;
    }

    private static boolean canAttack(LocalPlayer player, Entity entity) {
        return inAttackRange(player, entity) && player.hasLineOfSight(entity);
    }

    private static List<BlockPos> visibleApproachGoals(Minecraft client, Entity mob) {
        List<BlockPos> goals = new ArrayList<>(16);
        if (client.level == null) {
            return goals;
        }
        BlockPos center = mob.blockPosition();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double distSq = dx * dx + dz * dz;
                if (distSq < 4.0 || distSq > ATTACK_RANGE * ATTACK_RANGE) {
                    continue;
                }
                BlockPos candidate = center.offset(dx, 0, dz);
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos stand = candidate.offset(0, dy, 0);
                    if (BaritonePathfinder.isStandable(client.level, stand) && hasLineFrom(client, stand, mob)) {
                        goals.add(stand);
                        break;
                    }
                }
            }
        }
        goals.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        return goals;
    }

    private static boolean hasLineFrom(Minecraft client, BlockPos stand, Entity mob) {
        if (client.level == null || client.player == null) {
            return false;
        }
        Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + client.player.getEyeHeight(), stand.getZ() + 0.5);
        Vec3 aim = mob.getBoundingBox().getCenter();
        HitResult hit = client.level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean shouldAimWhilePathing(LocalPlayer player) {
        if (target == null) {
            return false;
        }
        if (horizontalDistanceSqr(player, target) <= PRE_AIM_RANGE * PRE_AIM_RANGE) {
            return true;
        }
        List<BlockPos> path = getPath();
        return path.size() > 1 && getPathIndex() >= path.size() - 2;
    }

    private boolean validTarget(Entity entity) {
        if (!(entity instanceof LivingEntity living) || entity == Minecraft.getInstance().player) {
            return false;
        }
        if (!living.isAlive() || living.isRemoved() || living.getHealth() <= 0.0f) {
            return false;
        }
        if (entity.getId() == oneTapSkippedId && oneTapSkipTicks > 0) {
            return false;
        }
        for (TargetKind kind : TargetKind.ORDER) {
            if (enabled(kind) && matches(kind, entity)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyEnabled() {
        for (TargetKind kind : TargetKind.ORDER) {
            if (enabled(kind)) {
                return true;
            }
        }
        return false;
    }

    private boolean enabled(TargetKind kind) {
        ConfigManager.Settings s = config.settings;
        return switch (kind) {
            case ZOMBIE -> s.combatZombie;
            case SKELETON -> s.combatSkeleton;
            case CREEPER -> s.combatCreeper;
            case CHARGED_CREEPER -> s.combatChargedCreeper;
            case SPIDER -> s.combatSpider;
            case ENDERMAN -> s.combatEnderman;
            case WITCH -> s.combatWitch;
            case SLIME -> s.combatSlime;
            case NETHER -> s.combatNetherMobs;
            case ILLAGER -> s.combatIllagers;
            case GUARDIAN -> s.combatGuardians;
            case ARTHROPOD -> s.combatArthropods;
            case WARDEN_BREEZE -> s.combatWardenBreeze;
            case ANIMALS -> s.combatAnimals;
            case AQUATIC -> s.combatAquatic;
            case VILLAGERS_GOLEMS -> s.combatVillagersGolems;
        };
    }

    private static boolean matches(TargetKind kind, Entity entity) {
        EntityType<?> type = entity.getType();
        return switch (kind) {
            case ZOMBIE -> type == EntityType.ZOMBIE || type == EntityType.HUSK || type == EntityType.DROWNED;
            case SKELETON -> type == EntityType.SKELETON || type == EntityType.STRAY || type == EntityType.BOGGED;
            case CREEPER -> type == EntityType.CREEPER && !isChargedCreeper(entity);
            case CHARGED_CREEPER -> isChargedCreeper(entity);
            case SPIDER -> type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER;
            case ENDERMAN -> type == EntityType.ENDERMAN;
            case WITCH -> type == EntityType.WITCH;
            case SLIME -> type == EntityType.SLIME || type == EntityType.MAGMA_CUBE;
            case NETHER -> type == EntityType.BLAZE || type == EntityType.GHAST || type == EntityType.WITHER_SKELETON
                    || type == EntityType.ZOMBIFIED_PIGLIN || type == EntityType.PIGLIN || type == EntityType.PIGLIN_BRUTE
                    || type == EntityType.HOGLIN || type == EntityType.ZOGLIN;
            case ILLAGER -> type == EntityType.PILLAGER || type == EntityType.VINDICATOR || type == EntityType.EVOKER
                    || type == EntityType.VEX || type == EntityType.RAVAGER;
            case GUARDIAN -> type == EntityType.GUARDIAN || type == EntityType.ELDER_GUARDIAN;
            case ARTHROPOD -> type == EntityType.SILVERFISH || type == EntityType.ENDERMITE;
            case WARDEN_BREEZE -> type == EntityType.WARDEN || type == EntityType.BREEZE;
            case ANIMALS -> animalTypes(type);
            case AQUATIC -> aquaticTypes(type);
            case VILLAGERS_GOLEMS -> type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER || type == EntityType.IRON_GOLEM;
        };
    }

    private static boolean isChargedCreeper(Entity entity) {
        return entity instanceof Creeper creeper && creeper.isPowered();
    }

    private static boolean animalTypes(EntityType<?> type) {
        return type == EntityType.COW || type == EntityType.PIG || type == EntityType.SHEEP || type == EntityType.CHICKEN
                || type == EntityType.RABBIT || type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE
                || type == EntityType.LLAMA || type == EntityType.TRADER_LLAMA || type == EntityType.CAMEL || type == EntityType.WOLF
                || type == EntityType.CAT || type == EntityType.OCELOT || type == EntityType.FOX || type == EntityType.PANDA
                || type == EntityType.POLAR_BEAR || type == EntityType.GOAT || type == EntityType.FROG || type == EntityType.TURTLE
                || type == EntityType.ARMADILLO || type == EntityType.AXOLOTL || type == EntityType.BAT || type == EntityType.PARROT
                || type == EntityType.BEE || type == EntityType.SNIFFER;
    }

    private static boolean aquaticTypes(EntityType<?> type) {
        return type == EntityType.COD || type == EntityType.SALMON || type == EntityType.TROPICAL_FISH
                || type == EntityType.PUFFERFISH || type == EntityType.SQUID || type == EntityType.GLOW_SQUID
                || type == EntityType.DOLPHIN;
    }

    private enum TargetKind {
        CHARGED_CREEPER,
        CREEPER,
        ZOMBIE,
        SKELETON,
        SPIDER,
        ENDERMAN,
        WITCH,
        SLIME,
        NETHER,
        ILLAGER,
        GUARDIAN,
        ARTHROPOD,
        WARDEN_BREEZE,
        ANIMALS,
        AQUATIC,
        VILLAGERS_GOLEMS;

        static final TargetKind[] ORDER = values();
    }
}
