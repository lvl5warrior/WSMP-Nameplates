package com.warriorssmp.nameplate;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Talks to PlaceholderAPI through reflection rather than a compile-time
 * dependency, so this plugin still loads and runs (with placeholders left as
 * literal text) on a server that doesn't have PAPI installed.
 */
public final class PlaceholderBridge {

    private final JavaPlugin plugin;

    private Method setPlaceholdersMethod;
    private boolean attemptedLookup;

    public PlaceholderBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        var papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        return papi != null && papi.isEnabled() && resolveMethod() != null;
    }

    public String resolve(OfflinePlayer player, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Method method = resolveMethod();
        if (method == null) {
            return text;
        }
        try {
            Object result = method.invoke(null, player, text);
            return result instanceof String string ? string : text;
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "PlaceholderAPI call failed for text '" + text + "'", throwable);
            return text;
        }
    }

    private Method resolveMethod() {
        if (setPlaceholdersMethod != null || attemptedLookup) {
            return setPlaceholdersMethod;
        }
        attemptedLookup = true;
        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = papiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.FINE, "PlaceholderAPI not present", throwable);
        }
        return setPlaceholdersMethod;
    }
}
