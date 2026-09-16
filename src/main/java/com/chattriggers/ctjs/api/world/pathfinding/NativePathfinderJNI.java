package com.chattriggers.ctjs.api.world.pathfinding;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NativePathfinderJNI {
    private static final String LIB_BASE = "V5PathJNI";
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String loadError;

    private NativePathfinderJNI() {
    }

    static boolean initialize() {
        if (initialized) {
            return available;
        }
        synchronized (NativePathfinderJNI.class) {
            if (initialized) {
                return available;
            }
            List<String> errors = new ArrayList<>();
            for (String resource : nativeResourceCandidates()) {
                try {
                    if (loadNativeFromResource(resource)) {
                        available = true;
                        loadError = null;
                        initialized = true;
                        return true;
                    }
                    errors.add(resource + ": not found in jar");
                } catch (Throwable t) {
                    errors.add(resource + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                }
            }
            available = false;
            loadError = "Failed to load native pathfinder. Tried: " + String.join(", ", errors);
            initialized = true;
            return false;
        }
    }

    private static boolean loadNativeFromResource(String resourcePath) throws Exception {
        String ext = extensionForOs(System.getProperty("os.name").toLowerCase(Locale.ROOT));
        var input = NativePathfinderJNI.class.getResourceAsStream(resourcePath);
        if (input == null) {
            return false;
        }
        File temp = Files.createTempFile(LIB_BASE, ext).toFile();
        temp.deleteOnExit();
        try (input; FileOutputStream out = new FileOutputStream(temp)) {
            input.transferTo(out);
        }
        System.load(temp.getAbsolutePath());
        if (!initNative()) {
            throw new IllegalStateException("initNative() returned false");
        }
        return true;
    }

    private static List<String> nativeResourceCandidates() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = normalizeArch(System.getProperty("os.arch"));
        String lib = LIB_BASE + extensionForOs(os);
        if (os.contains("win")) {
            return List.of("/assets/v5/natives/windows/" + arch + "/" + lib);
        }
        if (os.contains("linux")) {
            return arch.equals("x86_64")
                    ? List.of("/assets/v5/natives/linux/x86_64/" + lib)
                    : List.of("/assets/v5/natives/linux/" + arch + "/" + lib, "/assets/v5/natives/linux/x86_64/" + lib);
        }
        if (os.contains("mac")) {
            return arch.equals("arm64")
                    ? List.of("/assets/v5/natives/macos/arm64/" + lib, "/assets/v5/natives/macos/universal/" + lib)
                    : List.of("/assets/v5/natives/macos/x86_64/" + lib, "/assets/v5/natives/macos/universal/" + lib);
        }
        return List.of();
    }

    private static String extensionForOs(String os) {
        if (os.contains("win")) return ".dll";
        if (os.contains("mac")) return ".dylib";
        if (os.contains("linux")) return ".so";
        throw new IllegalStateException("Unsupported OS for native pathfinder: " + os);
    }

    private static String normalizeArch(String arch) {
        String a = arch.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> a;
        };
    }

    static boolean isAvailable() {
        return initialize();
    }

    static String getLoadError() {
        return loadError;
    }

    static native boolean initNative();
    static native void setWorld(String worldKey, int minY, int maxY);
    static native void clearWorld();
    static native void upsertChunk(int chunkX, int chunkZ, int minY, int maxY, long sectionMask, short[] sectionFlags);
    static native NativePathResult findPath(int[] startPoints, int[] endPoints, boolean isFly, int maxIterations,
                                            double heuristicWeight, double nonPrimaryStartPenalty,
                                            int moveOrderOffset, int[] avoidMeta, double[] avoidPenalty);
    static native void cancelSearch();
}
