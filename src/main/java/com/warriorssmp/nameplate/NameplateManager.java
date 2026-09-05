package com.warriorssmp.nameplate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the multi-line nameplate above each player's head.
 *
 * Minecraft gives you exactly ONE line above a player - the prefix/suffix of
 * whatever scoreboard team they're in, which is the same text as their tab
 * list entry. So a second line isn't a matter of writing more text: it needs a
 * separate entity. This spawns one TextDisplay per player and mounts it on
 * them, which means the client interpolates its position along with the player
 * instead of it visibly lagging behind on every step, and then hides the real
 * vanilla nametag so the two don't sit on top of each other.
 *
 * Config is re-read every refresh, so "/nameplate reload" applies live.
 */
public final class NameplateManager implements Listener {

    /** Tagged on every display we spawn so strays can be swept up after a crash or hot reload. */
    private static final String ENTITY_TAG = "wsmp_nameplate";

    /** Only used on scoreboards where the player isn't already in someone else's team. */
    private static final String FALLBACK_TEAM = "wsmp_np_hide";

    private static final Pattern JOIN_PATTERN = Pattern.compile("\\{join:([^{}]*)}");
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("\\s*\\|\\|\\s*");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Vanilla's own nametag background is 25% black - matched so "default" looks native. */
    private static final Color VANILLA_BACKGROUND = Color.fromARGB(64, 0, 0, 0);
    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);

    private final NameplatePlugin plugin;
    private final TextResolver textResolver;
    private final Map<UUID, TextDisplay> displays = new HashMap<>();

    private BukkitTask task;
    /** Style values currently pushed to the displays; re-applied only when the config actually changes. */
    private String appliedStyle = "";

    public NameplateManager(NameplatePlugin plugin, TextResolver textResolver) {
        this.plugin = plugin;
        this.textResolver = textResolver;
    }

    public void start() {
        removeStrayNameplates();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(1L, plugin.getConfig().getLong("update-interval-ticks", 20L));
        // The task runs even while enabled is false, so flipping that flag with a
        // reload takes effect immediately rather than at the next restart.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, interval);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TextDisplay display : new ArrayList<>(displays.values())) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            setVanillaNametagHidden(player, false);
        }
    }

    // ------------------------------------------------------------------
    // Refresh loop
    // ------------------------------------------------------------------

    public void refreshAll() {
        FileConfiguration config = plugin.getConfig();
        String style = styleSignature(config);
        boolean styleChanged = !style.equals(appliedStyle);
        appliedStyle = style;

        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshOne(player, styleChanged);
        }
    }

    public void refreshOne(Player player) {
        refreshOne(player, true);
    }

    private void refreshOne(Player player, boolean restyle) {
        if (player == null || !player.isOnline()) {
            return;
        }
        FileConfiguration config = plugin.getConfig();

        if (!config.getBoolean("enabled", true)) {
            forget(player);
            setVanillaNametagHidden(player, false);
            return;
        }

        setVanillaNametagHidden(player, config.getBoolean("hide-vanilla-nametag", true));

        // A dead player has already ejected its passengers; re-mounting onto the
        // corpse just leaves floating text at the death spot until they respawn.
        if (player.isDead()) {
            forget(player);
            return;
        }

        TextDisplay display = displays.get(player.getUniqueId());
        boolean detached = display != null
                && (!display.isValid() || !player.getPassengers().contains(display));
        if (detached) {
            // Happens on world change, on death, or if something else dismounted
            // it. The old entity is orphaned, so drop it and start clean.
            if (display.isValid()) {
                display.remove();
            }
            displays.remove(player.getUniqueId());
            display = null;
        }
        if (display == null) {
            display = spawnFor(player, config);
            if (display == null) {
                return;
            }
            restyle = true;
        }

        if (restyle) {
            applyStyle(display, config);
        }

        if (isHidden(player, config)) {
            // Cheaper and less flickery than despawning: empty text plus a fully
            // transparent background renders as nothing at all.
            display.text(Component.empty());
            display.setBackgroundColor(TRANSPARENT);
        } else {
            display.setBackgroundColor(parseBackground(config.getString("background", "default")));
            display.text(buildText(player, config));
        }
    }

    public void forget(Player player) {
        TextDisplay display = displays.remove(player.getUniqueId());
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    // ------------------------------------------------------------------
    // The display entity
    // ------------------------------------------------------------------

    private TextDisplay spawnFor(Player player, FileConfiguration config) {
        try {
            TextDisplay display = player.getWorld().spawn(player.getLocation(), TextDisplay.class);
            display.addScoreboardTag(ENTITY_TAG);
            display.setPersistent(false);
            display.text(Component.empty());

            if (!player.addPassenger(display)) {
                display.remove();
                debug("Could not mount a nameplate on " + player.getName()
                        + " (addPassenger returned false) - they get no nameplate this cycle.", null);
                return null;
            }
            if (config.getBoolean("hide-from-self", true)) {
                // Vanilla never shows you your own nametag; match that.
                player.hideEntity(plugin, display);
            }
            displays.put(player.getUniqueId(), display);
            return display;
        } catch (Throwable throwable) {
            debug("Failed to spawn a nameplate for " + player.getName(), throwable);
            return null;
        }
    }

    private void applyStyle(TextDisplay display, FileConfiguration config) {
        try {
            float yOffset = (float) config.getDouble("y-offset", 0.9D);
            float scale = (float) config.getDouble("scale", 1.0D);

            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setSeeThrough(config.getBoolean("see-through-walls", true));
            display.setShadowed(config.getBoolean("text-shadow", false));
            display.setViewRange((float) config.getDouble("view-range", 1.0D));
            display.setTransformation(new Transformation(
                    new Vector3f(0f, yOffset, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));

            if (config.getBoolean("full-bright", true)) {
                // Unlike a vanilla nametag, a text display dims with the ambient
                // light unless it's pinned to full brightness - without this the
                // nameplate is hard to read at night or underground.
                display.setBrightness(new Display.Brightness(15, 15));
            } else {
                display.setBrightness(null);
            }
        } catch (Throwable throwable) {
            debug("Failed to apply nameplate style (check the values in config.yml)", throwable);
        }
    }

    /** Anything in here changing means the displays need their style pushed again. */
    private String styleSignature(FileConfiguration config) {
        return config.getDouble("y-offset", 0.9D)
                + "|" + config.getDouble("scale", 1.0D)
                + "|" + config.getDouble("view-range", 1.0D)
                + "|" + config.getBoolean("see-through-walls", true)
                + "|" + config.getBoolean("text-shadow", false)
                + "|" + config.getBoolean("full-bright", true);
    }

    private Color parseBackground(String raw) {
        String value = raw == null ? "default" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("default")) {
            return VANILLA_BACKGROUND;
        }
        if (value.equalsIgnoreCase("transparent") || value.equalsIgnoreCase("none")) {
            return TRANSPARENT;
        }
        try {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            if (hex.length() == 6) {
                hex = "FF" + hex; // no alpha given - assume fully opaque
            }
            long argb = Long.parseLong(hex, 16);
            return Color.fromARGB(
                    (int) ((argb >> 24) & 0xFF),
                    (int) ((argb >> 16) & 0xFF),
                    (int) ((argb >> 8) & 0xFF),
                    (int) (argb & 0xFF));
        } catch (Throwable throwable) {
            debug("background is '" + raw + "', which isn't 'default', 'transparent' or a hex colour like "
                    + "#80000000 - falling back to the vanilla background.", throwable);
            return VANILLA_BACKGROUND;
        }
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    public Component buildText(Player player, FileConfiguration config) {
        List<String> rawLines = config.getStringList("lines");
        if (rawLines.isEmpty()) {
            rawLines = List.of("{rank} &f{name}");
        }
        List<String> blankValues = config.getStringList("blank-values");
        String separator = textResolver.resolve(player, config.getString("join-separator", " &8| "));

        List<Component> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            if (rawLine == null) {
                continue;
            }
            String resolved = applyJoinTokens(textResolver.resolve(player, rawLine), separator, blankValues);
            if (isBlank(resolved, blankValues)) {
                // A line that resolved to nothing (say the player has no team AND
                // no path yet) is dropped rather than left as an empty gap.
                continue;
            }
            lines.add(LEGACY.deserialize(resolved));
        }
        if (lines.isEmpty()) {
            return Component.empty();
        }

        // Built on an empty root so each line keeps its own colours instead of
        // inheriting whatever the line above happened to end on.
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append(Component.newline());
            }
            builder.append(lines.get(i));
        }
        return builder.build();
    }

    /**
     * Resolves the {join: A || B || C} token: drops the segments that came back
     * empty and glues the rest together with join-separator, so a player with no
     * team and no path shows "Grunt" instead of "Grunt |  | ". Runs after
     * TextResolver, so every segment here is already finished text.
     */
    private String applyJoinTokens(String text, String separator, List<String> blankValues) {
        if (text == null || !text.contains("{join:")) {
            return text;
        }
        Matcher matcher = JOIN_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            List<String> kept = new ArrayList<>();
            for (String segment : SEGMENT_SPLIT.split(matcher.group(1))) {
                String trimmed = segment.trim();
                if (!isBlank(trimmed, blankValues)) {
                    kept.add(trimmed);
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.join(separator, kept)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Blank = nothing but colour codes and whitespace, or a placeholder's "no value" word. */
    private boolean isBlank(String text, List<String> blankValues) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String stripped = ChatColor.stripColor(text);
        if (stripped == null || stripped.trim().isEmpty()) {
            return true;
        }
        String trimmed = stripped.trim();
        for (String blank : blankValues) {
            if (blank != null && trimmed.equalsIgnoreCase(blank.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isHidden(Player player, FileConfiguration config) {
        if (config.getBoolean("hide-when-sneaking", true) && player.isSneaking()) {
            return true;
        }
        if (config.getBoolean("hide-when-invisible", true)
                && player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return true;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        // The convention most vanish plugins use.
        return player.hasMetadata("vanished");
    }

    // ------------------------------------------------------------------
    // Vanilla nametag visibility
    // ------------------------------------------------------------------

    /**
     * Turns one player's real above-head name on or off, on every viewer's
     * scoreboard.
     *
     * Where WSMP-TabScoreboard is installed this reuses the per-player team it
     * already created, and only ever touches NAME_TAG_VISIBILITY - tab list
     * prefixes, suffixes and sorting are left completely alone. Where the player
     * isn't in any team (TabScoreboard absent or disabled) a bare team is made
     * just to carry the hide flag; if TabScoreboard later claims the player it
     * takes the entry over cleanly and this empty team is simply left unused.
     */
    private void setVanillaNametagHidden(Player target, boolean hidden) {
        Team.OptionStatus wanted = hidden ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try {
                Scoreboard board = viewer.getScoreboard();
                if (board == null) {
                    continue;
                }
                Team team = board.getEntryTeam(target.getName());
                if (team == null) {
                    if (!hidden) {
                        continue;
                    }
                    team = board.getTeam(FALLBACK_TEAM);
                    if (team == null) {
                        team = board.registerNewTeam(FALLBACK_TEAM);
                    }
                    team.addEntry(target.getName());
                }
                // Only write when it differs, otherwise every tick resends a team
                // packet to everyone for no reason.
                if (team.getOption(Team.Option.NAME_TAG_VISIBILITY) != wanted) {
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, wanted);
                }
                if (!hidden && FALLBACK_TEAM.equals(team.getName())) {
                    team.removeEntry(target.getName());
                }
            } catch (Throwable throwable) {
                debug("Failed to set nametag visibility for " + target.getName()
                        + " on " + viewer.getName() + "'s scoreboard", throwable);
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // A couple of ticks late: WSMP-TabScoreboard builds this player's team on
        // join too, and the other WSMP plugins may still be loading their data.
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshOne(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshOne(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshOne(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        // Without this the nameplate would linger for up to a full refresh
        // interval after the player starts or stops sneaking.
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshOne(event.getPlayer()), 1L);
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    /**
     * Sweeps up nameplates left behind by a crash or a hot plugin reload. They're
     * spawned non-persistent so a normal restart clears them already, but a
     * PlugMan-style reload would otherwise leave floating text everywhere.
     */
    private void removeStrayNameplates() {
        try {
            Bukkit.getWorlds().forEach(world ->
                    world.getEntitiesByClass(TextDisplay.class).forEach(display -> {
                        if (display.getScoreboardTags().contains(ENTITY_TAG)) {
                            display.remove();
                        }
                    }));
        } catch (Throwable throwable) {
            debug("Failed to sweep up leftover nameplate entities", throwable);
        }
    }

    /**
     * WSMP house style: surface the real error in chat to admins, not just in the
     * console, so a broken nameplate is obvious in-game while testing.
     */
    private void debug(String message, Throwable throwable) {
        String detail = throwable == null
                ? message
                : message + " - " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        plugin.getLogger().warning(detail);
        String chat = ChatColor.translateAlternateColorCodes('&', "&c[Nameplate] &7" + detail);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("wsmpnameplate.admin")) {
                player.sendMessage(chat);
            }
        }
    }
}
