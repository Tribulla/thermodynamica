package com.Tribulla.thermodynamica.network;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class HeatDebugPlayers {

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    private HeatDebugPlayers() {
    }

    public static boolean isEnabled(ServerPlayer player) {
        return ENABLED.contains(player.getUUID());
    }

    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (ENABLED.contains(id)) {
            ENABLED.remove(id);
            return false;
        }
        ENABLED.add(id);
        return true;
    }

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) {
            ENABLED.add(player.getUUID());
        } else {
            ENABLED.remove(player.getUUID());
        }
    }

    public static void remove(ServerPlayer player) {
        ENABLED.remove(player.getUUID());
    }

    public static void clear() {
        ENABLED.clear();
    }
}
