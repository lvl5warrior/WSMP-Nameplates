package com.warriorssmp.nameplate;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Reads a player's PvP rank straight out of WSMP-Duels for the {rank} token.
 *
 * Done by reflection on purpose: it means WSMP-Duels doesn't have to be a
 * build dependency, and if it's missing, disabled, or its internals change,
 * {rank} quietly goes blank instead of the nameplates dying entirely. This
 * mirrors what WSMP-TabScoreboard already does for its sidebar, so both read
 * the same value and show the same earned tier colouring.
 */
public final class HonorBridge {

    private final JavaPlugin plugin;

    public HonorBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        Plugin duels = plugin.getServer().getPluginManager().getPlugin("WSMP-Duels");
        return duels != null && duels.isEnabled();
    }

    /** The coloured rank name, or an empty string if it can't be read. */
    public String getRankDisplay(Player player) {
        Plugin duels = plugin.getServer().getPluginManager().getPlugin("WSMP-Duels");
        if (duels == null || !duels.isEnabled()) {
            return "";
        }
        try {
            Object playerDataManager = duels.getClass().getMethod("getPlayerDataManager").invoke(duels);
            Object playerData = playerDataManager.getClass()
                    .getMethod("get", UUID.class)
                    .invoke(playerDataManager, player.getUniqueId());
            if (playerData == null) {
                return "";
            }
            int lifetimeHonor = (Integer) playerData.getClass().getMethod("getLifetimeHonor").invoke(playerData);

            Class<?> honorRankClass = Class.forName("com.warriorssmp.duels.HonorRank");
            Object rank = honorRankClass.getMethod("forLifetimeHonor", int.class).invoke(null, lifetimeHonor);
            if (rank == null) {
                return "";
            }
            Object displayName = rank.getClass().getMethod("getDisplayName").invoke(rank);
            return displayName == null ? "" : displayName.toString();
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE,
                    "Could not read Honor Rank for " + player.getName() + " from WSMP-Duels", throwable);
            return "";
        }
    }
}
