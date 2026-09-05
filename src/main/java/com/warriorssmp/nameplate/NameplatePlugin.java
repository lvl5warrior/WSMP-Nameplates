package com.warriorssmp.nameplate;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WSMP-Nameplate - the floating name above a player's head, and nothing else.
 *
 * Deliberately kept separate from WSMP-TabScoreboard: the tab list and the
 * sidebar are perfectly happy as they are, and a bug in the display-entity
 * handling here can't take them down with it. Pull this jar out and the
 * server goes straight back to normal vanilla nametags.
 */
public final class NameplatePlugin extends JavaPlugin {

    private PlaceholderBridge placeholderBridge;
    private HonorBridge honorBridge;
    private TextResolver textResolver;
    private NameplateManager nameplateManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        placeholderBridge = new PlaceholderBridge(this);
        honorBridge = new HonorBridge(this);
        textResolver = new TextResolver(this, placeholderBridge, honorBridge);
        nameplateManager = new NameplateManager(this, textResolver);
        nameplateManager.start();

        if (!placeholderBridge.isAvailable()) {
            getLogger().warning("PlaceholderAPI isn't installed/enabled - any %placeholder% text in config.yml "
                    + "will show up unresolved until it's added.");
        }
        if (!honorBridge.isAvailable()) {
            getLogger().warning("WSMP-Duels isn't installed/enabled - {rank} will show blank until it's added.");
        }

        PluginCommand command = getCommand("nameplate");
        if (command != null) {
            command.setExecutor(new NameplateCommand(this));
        }

        getLogger().info("WSMP-Nameplate enabled.");
    }

    @Override
    public void onDisable() {
        if (nameplateManager != null) {
            nameplateManager.shutdown();
        }
    }

    public PlaceholderBridge getPlaceholderBridge() {
        return placeholderBridge;
    }

    public HonorBridge getHonorBridge() {
        return honorBridge;
    }

    public TextResolver getTextResolver() {
        return textResolver;
    }

    public NameplateManager getNameplateManager() {
        return nameplateManager;
    }
}
