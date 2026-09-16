package com.booter.client.recorder;

import com.booter.client.BooterClient;
import com.booter.client.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MovementRecorder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type NODE_LIST = new TypeToken<List<RecordedMovementNode>>() {}.getType();

    private final ConfigManager config;
    private final List<RecordedMovementNode> nodes = new ArrayList<>();
    private final List<RecordedMovementNode> view = Collections.unmodifiableList(nodes);
    private boolean recording;
    private int skippedAirTicks;

    public MovementRecorder(ConfigManager config) {
        this.config = config;
    }

    public void tick(Minecraft client) {
        if (!recording) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        if (!player.onGround()) {
            skippedAirTicks++;
            return;
        }
        double min = config.settings.recorderNodeSpacing;
        if (nodes.isEmpty() || last().type == RecordedMovementNode.Type.END
                || last().squaredDistanceTo(player.getX(), player.getY(), player.getZ()) >= min * min) {
            addNode(player, RecordedMovementNode.Type.WALK);
        }
    }

    public void start(Minecraft client) {
        recording = true;
        skippedAirTicks = 0;
        BooterClient.chat("Movement recorder started. Ground movement only.");
        tick(client);
    }

    public void stop() {
        recording = false;
        BooterClient.chat("Movement recorder stopped with " + nodes.size() + " node(s).");
    }

    public void toggle(Minecraft client) {
        if (recording) {
            stop();
        } else {
            start(client);
        }
    }

    public void clear() {
        int count = nodes.size();
        nodes.clear();
        skippedAirTicks = 0;
        BooterClient.chat("Cleared " + count + " recorded movement node(s).");
    }

    public int addEndNode(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return -1;
        }
        addNode(player, RecordedMovementNode.Type.END);
        BooterClient.chat("Added END node #" + nodes.size() + ".");
        return nodes.size();
    }

    public String save(String name) {
        String fileName = sanitize(name) + ".json";
        Path file = recordingsDir().resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file)) {
                GSON.toJson(nodes, NODE_LIST, writer);
            }
            BooterClient.chat("Saved movement recording: " + fileName + ".");
            return fileName;
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to save movement recording {}", file, e);
            BooterClient.chat("Failed to save movement recording.");
            return null;
        }
    }

    public Path recordingsDir() {
        Path dir = config.routesDir().getParent().resolve("movement_recordings");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            BooterClient.LOGGER.error("Failed to create movement recordings directory", e);
        }
        return dir;
    }

    public List<RecordedMovementNode> view() {
        return view;
    }

    public boolean isRecording() {
        return recording;
    }

    public int size() {
        return nodes.size();
    }

    public int endNodeCount() {
        int count = 0;
        for (RecordedMovementNode node : nodes) {
            if (node.type == RecordedMovementNode.Type.END) {
                count++;
            }
        }
        return count;
    }

    public int skippedAirTicks() {
        return skippedAirTicks;
    }

    private RecordedMovementNode last() {
        return nodes.get(nodes.size() - 1);
    }

    private void addNode(LocalPlayer player, RecordedMovementNode.Type type) {
        nodes.add(new RecordedMovementNode(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), type));
    }

    private static String sanitize(String name) {
        String cleaned = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        return cleaned.isBlank() ? "movement_route" : cleaned;
    }
}
