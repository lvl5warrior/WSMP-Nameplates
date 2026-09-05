package com.warriorssmp.nameplate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one raw config line into finished, coloured text.
 *
 * Order matters here: built-in tokens first (so a token can't be mangled by a
 * placeholder's output), then {strip:...}, then PlaceholderAPI, then
 * MiniMessage tags, then legacy '&' codes last.
 */
public final class TextResolver {

    private static final Pattern STRIP_PATTERN = Pattern.compile("\\{strip:(%[^%]+%)}");

    private final NameplatePlugin plugin;
    private final PlaceholderBridge placeholderBridge;
    private final HonorBridge honorBridge;

    public TextResolver(NameplatePlugin plugin, PlaceholderBridge placeholderBridge, HonorBridge honorBridge) {
        this.plugin = plugin;
        this.placeholderBridge = placeholderBridge;
        this.honorBridge = honorBridge;
    }

    public String resolve(Player player, String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String text = raw;
        if (text.contains("{name}")) {
            text = text.replace("{name}", player.getName());
        }
        if (text.contains("{rank}")) {
            text = text.replace("{rank}", honorBridge.getRankDisplay(player));
        }
        if (text.contains("{strip:")) {
            text = resolveStrippedPlaceholders(player, text);
        }
        text = placeholderBridge.resolve(player, text);
        text = resolveMiniMessageTags(text);
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Resolves a placeholder and throws away whatever colours it came with, so
     * a colour set here in config is the one that actually shows instead of
     * being overridden partway through by the other plugin's own choice.
     */
    private String resolveStrippedPlaceholders(Player player, String text) {
        Matcher matcher = STRIP_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String resolved = placeholderBridge.resolve(player, matcher.group(1));
            String stripped = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', resolved));
            matcher.appendReplacement(result, Matcher.quoteReplacement(stripped == null ? "" : stripped));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveMiniMessageTags(String text) {
        if (text == null || text.indexOf('<') < 0) {
            return text;
        }
        try {
            Component component = MiniMessage.miniMessage().deserialize(text);
            return LegacyComponentSerializer.legacySection().serialize(component);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to parse MiniMessage tags in text '" + text + "': "
                    + throwable.getClass().getSimpleName() + " - " + throwable.getMessage());
            return text;
        }
    }
}
