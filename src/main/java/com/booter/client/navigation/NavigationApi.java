package com.booter.client.navigation;

import com.booter.client.BooterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class NavigationApi {
    private NavigationApi() {
    }

    public static void navigateTo(Minecraft client, int x, int y, int z) {
        BooterClient.pathfinder().startLand(client, x, y, z);
    }

    public static void navigateTo(Minecraft client, BlockPos pos) {
        if (pos != null) {
            navigateTo(client, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public static void cancelNavigation(Minecraft client) {
        BooterClient.pathfinder().stop(client);
        if (BooterClient.navigation() != null) {
            BooterClient.navigation().status().idle();
        }
    }

    public static boolean isNavigating() {
        return BooterClient.pathfinder().getState() == com.booter.client.pathfinder.PathfinderModule.State.FOLLOWING;
    }

    public static List<BlockPos> getCurrentPath() {
        return BooterClient.pathfinder().getPath();
    }

    public static NavigationStatus getNavigationStatus() {
        return BooterClient.navigation().status();
    }
}
