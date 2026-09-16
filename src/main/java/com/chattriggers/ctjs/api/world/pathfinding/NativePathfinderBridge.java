package com.chattriggers.ctjs.api.world.pathfinding;

public final class NativePathfinderBridge {
    private static volatile String lastError;

    private NativePathfinderBridge() {
    }

    public record NativePathSearchRequest(int[] startPoints, int[] endPoints, boolean isFly, int maxIterations,
                                          double heuristicWeight, double nonPrimaryStartPenalty,
                                          int moveOrderOffset, int[] avoidMeta, double[] avoidPenalty) {
    }

    public static boolean isAvailable() {
        return NativePathfinderJNI.isAvailable();
    }

    public static String getLastError() {
        return lastError != null ? lastError : NativePathfinderJNI.getLoadError();
    }

    public static void setWorld(String worldKey, int minY, int maxY) {
        runNative(() -> NativePathfinderJNI.setWorld(worldKey, minY, maxY));
    }

    public static void clearWorld() {
        runNative(NativePathfinderJNI::clearWorld);
    }

    public static void upsertChunk(int chunkX, int chunkZ, int minY, int maxY, long sectionMask, short[] sectionFlags) {
        runNative(() -> NativePathfinderJNI.upsertChunk(chunkX, chunkZ, minY, maxY, sectionMask, sectionFlags));
    }

    public static NativePathResult findPath(NativePathSearchRequest request) {
        if (!isAvailable()) {
            lastError = NativePathfinderJNI.getLoadError();
            return null;
        }
        try {
            NativePathResult result = NativePathfinderJNI.findPath(request.startPoints, request.endPoints, request.isFly,
                    request.maxIterations, request.heuristicWeight, request.nonPrimaryStartPenalty,
                    request.moveOrderOffset, request.avoidMeta, request.avoidPenalty);
            lastError = result == null ? "Native pathfinder returned no path" : null;
            return result;
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return null;
        }
    }

    public static void cancelSearch() {
        if (!isAvailable()) {
            return;
        }
        try {
            NativePathfinderJNI.cancelSearch();
        } catch (Throwable ignored) {
        }
    }

    private static void runNative(Runnable runnable) {
        if (!isAvailable()) {
            lastError = NativePathfinderJNI.getLoadError();
            return;
        }
        try {
            runnable.run();
            lastError = null;
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }
}
