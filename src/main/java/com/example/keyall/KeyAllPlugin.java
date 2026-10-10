package com.example.keyall;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeyAll has two modes:
 *  - global (default): one shared clock. When it reaches zero, every player who is
 *    online at that moment gets a key, then the clock restarts.
 *  - per-player: each player has their own playtime counter (old behaviour).
 */
public final class KeyAllPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> seconds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();

    private File dataFile;
    private boolean globalMode;
    private long intervalSeconds;
    private String crateId;
    private String giveCommand;
    private boolean countAfk;
    private long afkMillis;
    private String rewardMessage;
    private String broadcastMessage;
    private String reminderMessage;
    private Set<Long> reminderSeconds = new HashSet<>();

    // Remaining seconds on the shared clock (global mode). Volatile: read by placeholders.
    private volatile long secondsLeft;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();
        normalizeClock();

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastActive.put(p.getUniqueId(), now);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // Autosave every 5 minutes so a crash only loses a little progress.
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveData, 6000L, 6000L);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new KeyAllExpansion(this).register();
            getLogger().info("PlaceholderAPI found: %keyall_time% and %keyall_seconds% are available.");
        }
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        globalMode = !"per-player".equalsIgnoreCase(c.getString("mode", "global"));
        intervalSeconds = Math.max(1L, c.getLong("interval-minutes", 240L)) * 60L;
        crateId = c.getString("crate-id", "dlKEYALL");
        giveCommand = c.getString("command", "crate giveKey %crate% %player% 1");
        countAfk = c.getBoolean("count-afk-time", false);
        afkMillis = Math.max(1L, c.getLong("afk-after-seconds", 300L)) * 1000L;
        rewardMessage = c.getString("reward-message", "");
        broadcastMessage = c.getString("broadcast-message",
                "&8[&bKeyAll&8] &aEveryone online just received a &bKeyAll &akey! Next one in &e%interval%&a.");
        reminderMessage = c.getString("reminder-message",
                "&8[&bKeyAll&8] &7KeyAll keys are given out in &e%time%&7!");
        List<Integer> mins = c.contains("reminders-minutes")
                ? c.getIntegerList("reminders-minutes")
                : List.of(10, 1);
        Set<Long> secs = new HashSet<>();
        for (int m : mins) {
            if (m > 0) {
                secs.add(m * 60L);
            }
        }
        reminderSeconds = secs;
        normalizeClock();
    }

    /** Keep the shared clock within 1..interval. */
    private void normalizeClock() {
        if (secondsLeft <= 0 || secondsLeft > intervalSeconds) {
            secondsLeft = intervalSeconds;
        }
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        secondsLeft = y.getLong("global.seconds-left", 0L);
        ConfigurationSection section = y.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                seconds.put(UUID.fromString(key), section.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // skip invalid entries
            }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("global.seconds-left", secondsLeft);
        for (Map.Entry<UUID, Long> e : seconds.entrySet()) {
            y.set("players." + e.getKey(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void tick() {
        if (globalMode) {
            tickGlobal();
        } else {
            tickPerPlayer();
        }
    }

    private void tickGlobal() {
        long left = secondsLeft - 1;
        if (left <= 0) {
            payoutAll();
            secondsLeft = intervalSeconds;
            return;
        }
        secondsLeft = left;
        if (!reminderMessage.isEmpty() && reminderSeconds.contains(left)) {
            Bukkit.broadcast(color(reminderMessage.replace("%time%", format(left))));
        }
    }

    private void payoutAll() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player p : online) {
            runGive(p);
        }
        if (!broadcastMessage.isEmpty()) {
            Bukkit.broadcast(color(broadcastMessage.replace("%interval%", format(intervalSeconds))));
        }
    }

    private void tickPerPlayer() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!countAfk) {
                long last = lastActive.getOrDefault(id, now);
                if (now - last > afkMillis) {
                    continue; // idle, do not count this second
                }
            }
            long total = seconds.merge(id, 1L, Long::sum);
            if (total >= intervalSeconds) {
                seconds.put(id, 0L);
                runGive(p);
                if (!rewardMessage.isEmpty()) {
                    p.sendMessage(color(rewardMessage));
                }
            }
        }
    }

    private void runGive(Player p) {
        String cmd = giveCommand
                .replace("%player%", p.getName())
                .replace("%crate%", crateId);
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private static String format(long totalSeconds) {
        long t = Math.max(0L, totalSeconds);
        long h = t / 3600;
        long m = (t % 3600) / 60;
        long s = t % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return m + "m " + s + "s";
    }

    /** Used by the PlaceholderAPI expansion. Returns null for unknown placeholders. */
    String placeholder(OfflinePlayer player, String params) {
        long left;
        if (globalMode) {
            left = secondsLeft;
        } else {
            if (player == null) {
                return "";
            }
            left = Math.max(0L, intervalSeconds - seconds.getOrDefault(player.getUniqueId(), 0L));
        }
        switch (params.toLowerCase()) {
            case "time":
                return format(left);
            case "seconds":
                return String.valueOf(left);
            default:
                return null;
        }
    }

    private void touch(UUID id) {
        lastActive.put(id, System.currentTimeMillis());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        touch(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastActive.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!globalMode && (e.hasChangedPosition() || e.hasChangedOrientation())) {
            touch(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!globalMode) {
            touch(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        if (!globalMode) {
            touch(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!globalMode) {
            touch(e.getPlayer().getUniqueId());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("keyall.admin")) {
                sender.sendMessage(color("&cYou do not have permission."));
                return true;
            }
            loadSettings();
            sender.sendMessage(color("&aKeyAll config reloaded."));
            return true;
        }
        if (globalMode) {
            sender.sendMessage(color("&7Next &bKeyAll &7key for everyone online in &e"
                    + format(secondsLeft) + "&7."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can check their timer.");
            return true;
        }
        long left = Math.max(0L, intervalSeconds - seconds.getOrDefault(p.getUniqueId(), 0L));
        p.sendMessage(color("&7Next &bKeyAll &7key in &e" + format(left) + "&7 of playtime."));
        return true;
    }
}
