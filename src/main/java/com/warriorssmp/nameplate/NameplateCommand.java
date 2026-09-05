package com.warriorssmp.nameplate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Live-tuning commands. y-offset in particular is a "nudge it and look" value,
 * so being able to change it without opening a file and reloading matters more
 * here than it would for most settings.
 */
public final class NameplateCommand implements CommandExecutor {

    private final NameplatePlugin plugin;

    public NameplateCommand(NameplatePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getNameplateManager().refreshAll();
                send(sender, "&aConfig reloaded and every nameplate refreshed.");
            }
            case "test" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&cRun this in-game - it renders YOUR nameplate.");
                    return true;
                }
                Component text = plugin.getNameplateManager().buildText(player, plugin.getConfig());
                String rendered = LegacyComponentSerializer.legacySection().serialize(text);
                send(sender, "&7Your nameplate resolves to:");
                for (String line : rendered.split("\n")) {
                    sender.sendMessage("  " + line);
                }
                send(sender, "&7Any %placeholder% still showing above is one PlaceholderAPI doesn't know.");
            }
            case "offset", "scale" -> {
                if (args.length < 2) {
                    send(sender, "&cUsage: /nameplate " + args[0].toLowerCase() + " <number>");
                    return true;
                }
                String path = args[0].equalsIgnoreCase("offset") ? "y-offset" : "scale";
                double value;
                try {
                    value = Double.parseDouble(args[1]);
                } catch (NumberFormatException exception) {
                    send(sender, "&c'" + args[1] + "' isn't a number.");
                    return true;
                }
                plugin.getConfig().set(path, value);
                plugin.saveConfig();
                plugin.getNameplateManager().refreshAll();
                send(sender, "&aSet &f" + path + " &ato &f" + value + "&a.");
            }
            case "toggle" -> {
                boolean enabled = !plugin.getConfig().getBoolean("enabled", true);
                plugin.getConfig().set("enabled", enabled);
                plugin.saveConfig();
                plugin.getNameplateManager().refreshAll();
                send(sender, enabled
                        ? "&aNameplates on - vanilla nametags hidden again."
                        : "&eNameplates off - vanilla nametags restored.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        send(sender, "&c&lWSMP-Nameplate");
        send(sender, "&7/nameplate reload &8- &fre-read config.yml and refresh everyone");
        send(sender, "&7/nameplate test &8- &fshow what your own nameplate resolves to");
        send(sender, "&7/nameplate offset <n> &8- &fheight above the head, e.g. 0.9");
        send(sender, "&7/nameplate scale <n> &8- &ftext size, 1.0 is vanilla");
        send(sender, "&7/nameplate toggle &8- &fturn nameplates on/off without a restart");
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
