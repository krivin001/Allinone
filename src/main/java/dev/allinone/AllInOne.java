package dev.allinone;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * AllInOne - an all-in-one essentials plugin for Paper 26.1.2.
 * Homes, warps, spawn, TPA, back, economy, kits, mutes, chat, utilities.
 */
public final class AllInOne extends JavaPlugin implements Listener, TabExecutor {

    private static final String[] COMMANDS = {
            "sethome", "home", "delhome", "homes",
            "setwarp", "warp", "delwarp", "warps",
            "setspawn", "spawn",
            "tpa", "tpahere", "tpaccept", "tpdeny", "back",
            "balance", "pay", "baltop", "eco",
            "kit",
            "mute", "unmute",
            "heal", "feed", "fly", "gm", "god", "vanish",
            "invsee", "enderchest", "repair", "hat", "workbench", "speed",
            "day", "night", "sun", "rain",
            "broadcast", "msg", "reply", "ping", "near", "top",
            "shop", "sell", "sellall", "worth",
            "sit", "lay", "crawl", "scoreboard",
            "allinone"
    };

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,24}");

    private final MiniMessage mm = MiniMessage.miniMessage();

    private File dataFile;
    private YamlConfiguration data;

    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private final Map<UUID, Location> pendingTeleports = new HashMap<>();
    private final Map<UUID, TpaRequest> tpaRequests = new HashMap<>();
    private final Map<UUID, UUID> lastMessaged = new HashMap<>();
    private final Set<UUID> godMode = new HashSet<>();
    private final Set<UUID> vanished = new HashSet<>();

    private EconomyManager economy;
    private ShopManager shop;
    private ScoreboardManager scoreboards;
    private SitManager sit;
    private volatile boolean dirty;

    private record TpaRequest(UUID from, boolean here, long expiresAt) {}

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        economy = new EconomyManager(this);
        loadData();
        shop = new ShopManager(this, economy);
        scoreboards = new ScoreboardManager(this, economy);
        scoreboards.load(data);
        sit = new SitManager(this);

        for (String name : COMMANDS) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(sit, this);

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            VaultHook.register(this, economy);
        } else {
            getLogger().info("Vault not found - economy works in-game only (install Vault for shop plugins).");
        }

        scoreboards.start();
        // save changed data every 30 seconds
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (dirty) saveData();
        }, 600L, 600L);
        getLogger().info("AllInOne enabled.");
    }

    @Override
    public void onDisable() {
        if (sit != null) sit.shutdown();
        if (scoreboards != null) scoreboards.stop();
        saveData();
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isVanished(Player p) {
        return vanished.contains(p.getUniqueId());
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        if (economy != null) economy.load(data);
        mutes.clear();
        ConfigurationSection ms = data.getConfigurationSection("mutes");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                try {
                    mutes.put(UUID.fromString(key), ms.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private synchronized void saveData() {
        try {
            dirty = false;
            if (economy != null) economy.save(data);
            if (scoreboards != null) scoreboards.save(data);
            data.set("mutes", null);
            for (Map.Entry<UUID, Long> e : mutes.entrySet()) {
                data.set("mutes." + e.getKey(), e.getValue());
            }
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void send(CommandSender to, String mini, TagResolver... resolvers) {
        to.sendMessage(mm.deserialize(getConfig().getString("prefix", "") + mini, resolvers));
    }

    private static TagResolver ph(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    private Player requirePlayer(CommandSender s) {
        if (s instanceof Player p) return p;
        send(s, "<red>This command can only be used by players.");
        return null;
    }

    private Player findPlayer(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            send(s, "<red>Player <name> is not online.", ph("name", name));
        }
        return p;
    }

    private boolean perm(CommandSender s, String node) {
        if (s.hasPermission(node)) return true;
        send(s, "<red>You don't have permission to do that.");
        return false;
    }

    private String money(double amount) {
        return economy.format(amount);
    }

    private double balance(UUID id) {
        return economy.get(id);
    }

    private void setBalance(UUID id, double value) {
        economy.set(id, value);
    }

    private Double parseAmount(CommandSender s, String raw) {
        try {
            double v = Double.parseDouble(raw);
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) throw new NumberFormatException();
            return Math.floor(v * 100) / 100;
        } catch (NumberFormatException e) {
            send(s, "<red>Invalid amount.");
            return null;
        }
    }

    private static String ownerKey(Player p) {
        return p.getUniqueId().toString();
    }

    /** Teleports with optional warmup. Cancelled if the player moves. */
    private void teleport(Player p, Location dest, String label) {
        int delay = getConfig().getInt("teleport-delay", 3);
        if (delay <= 0 || p.hasPermission("allinone.bypass.delay")) {
            doTeleport(p, dest, label);
            return;
        }
        Location start = p.getLocation().clone();
        pendingTeleports.put(p.getUniqueId(), start);
        send(p, "<gray>Teleporting in <yellow><s></yellow> seconds. Don't move!", ph("s", String.valueOf(delay)));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pendingTeleports.get(p.getUniqueId()) == start) {
                pendingTeleports.remove(p.getUniqueId());
                if (p.isOnline()) doTeleport(p, dest, label);
            }
        }, delay * 20L);
    }

    private void doTeleport(Player p, Location dest, String label) {
        backLocations.put(p.getUniqueId(), p.getLocation().clone());
        p.teleportAsync(dest).thenAccept(ok -> {
            if (ok) send(p, "<green>Teleported to <yellow><l></yellow>.", ph("l", label));
        });
    }

    private int maxHomes(Player p) {
        if (p.hasPermission("allinone.homes.unlimited")) return Integer.MAX_VALUE;
        int max = getConfig().getInt("max-homes", 3);
        for (int i = 50; i > max; i--) {
            if (p.hasPermission("allinone.homes." + i)) return i;
        }
        return max;
    }

    private Set<String> homeNames(Player p) {
        ConfigurationSection s = data.getConfigurationSection("homes." + ownerKey(p));
        return s == null ? new HashSet<>() : s.getKeys(false);
    }

    private Set<String> warpNames() {
        ConfigurationSection s = data.getConfigurationSection("warps");
        return s == null ? new HashSet<>() : s.getKeys(false);
    }

    private Location spawnLocation() {
        Location l = data.getLocation("spawn");
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private void updateVanishFor(Player viewer) {
        for (UUID id : vanished) {
            Player v = Bukkit.getPlayer(id);
            if (v != null && !viewer.hasPermission("allinone.vanish.see")) viewer.hidePlayer(this, v);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "sethome" -> cmdSetHome(sender, args);
            case "home" -> cmdHome(sender, args);
            case "delhome" -> cmdDelHome(sender, args);
            case "homes" -> cmdHomes(sender);
            case "setwarp" -> cmdSetWarp(sender, args);
            case "warp" -> cmdWarp(sender, args);
            case "delwarp" -> cmdDelWarp(sender, args);
            case "warps" -> cmdWarps(sender);
            case "setspawn" -> cmdSetSpawn(sender);
            case "spawn" -> cmdSpawn(sender);
            case "tpa" -> cmdTpa(sender, args, false);
            case "tpahere" -> cmdTpa(sender, args, true);
            case "tpaccept" -> cmdTpAccept(sender, true);
            case "tpdeny" -> cmdTpAccept(sender, false);
            case "back" -> cmdBack(sender);
            case "balance" -> cmdBalance(sender, args);
            case "pay" -> cmdPay(sender, args);
            case "baltop" -> cmdBalTop(sender);
            case "eco" -> cmdEco(sender, args);
            case "kit" -> cmdKit(sender, args);
            case "mute" -> cmdMute(sender, args);
            case "unmute" -> cmdUnmute(sender, args);
            case "heal" -> cmdHeal(sender, args);
            case "feed" -> cmdFeed(sender, args);
            case "fly" -> cmdFly(sender, args);
            case "gm" -> cmdGm(sender, args);
            case "god" -> cmdGod(sender);
            case "vanish" -> cmdVanish(sender);
            case "invsee" -> cmdInvsee(sender, args, false);
            case "enderchest" -> cmdInvsee(sender, args, true);
            case "repair" -> cmdRepair(sender);
            case "hat" -> cmdHat(sender);
            case "workbench" -> cmdWorkbench(sender);
            case "speed" -> cmdSpeed(sender, args);
            case "day" -> cmdTime(sender, 1000L, "day");
            case "night" -> cmdTime(sender, 13000L, "night");
            case "sun" -> cmdWeather(sender, false);
            case "rain" -> cmdWeather(sender, true);
            case "broadcast" -> cmdBroadcast(sender, args);
            case "msg" -> cmdMsg(sender, args);
            case "reply" -> cmdReply(sender, args);
            case "ping" -> cmdPing(sender, args);
            case "near" -> cmdNear(sender, args);
            case "top" -> cmdTop(sender);
            case "shop" -> cmdShop(sender, args);
            case "sell" -> cmdSell(sender, args);
            case "sellall" -> { Player sp = requirePlayer(sender); if (sp != null) shop.sellAll(sp); }
            case "worth" -> { Player wp = requirePlayer(sender); if (wp != null) shop.worth(wp); }
            case "sit" -> { Player p1 = requirePlayer(sender); if (p1 != null && perm(p1, "allinone.sit")) sit.toggleSit(p1); }
            case "lay" -> { Player p2 = requirePlayer(sender); if (p2 != null && perm(p2, "allinone.lay")) sit.toggleLay(p2); }
            case "crawl" -> { Player p3 = requirePlayer(sender); if (p3 != null && perm(p3, "allinone.crawl")) sit.toggleCrawl(p3); }
            case "scoreboard" -> cmdScoreboard(sender);
            case "allinone" -> cmdAllInOne(sender, args);
            default -> { return false; }
        }
        return true;
    }

    // ---- homes

    private void cmdSetHome(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        String n = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "home";
        if (!NAME.matcher(n).matches()) {
            send(p, "<red>Home names may only contain letters, numbers, - and _ (max 24).");
            return;
        }
        Set<String> existing = homeNames(p);
        if (!existing.contains(n) && existing.size() >= maxHomes(p)) {
            send(p, "<red>You've reached your home limit (<n>).", ph("n", String.valueOf(maxHomes(p))));
            return;
        }
        data.set("homes." + ownerKey(p) + "." + n, p.getLocation());
        saveData();
        send(p, "<green>Home <yellow><n></yellow> set.", ph("n", n));
    }

    private void cmdHome(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        String n = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "home";
        Location l = data.getLocation("homes." + ownerKey(p) + "." + n);
        if (l == null) {
            send(p, "<red>Home <n> not found. Your homes: <list>", ph("n", n), ph("list", String.join(", ", homeNames(p))));
            return;
        }
        teleport(p, l, "home " + n);
    }

    private void cmdDelHome(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 1) {
            send(p, "<red>Usage: /delhome <name>");
            return;
        }
        String n = a[0].toLowerCase(Locale.ROOT);
        if (!homeNames(p).contains(n)) {
            send(p, "<red>Home <n> not found.", ph("n", n));
            return;
        }
        data.set("homes." + ownerKey(p) + "." + n, null);
        saveData();
        send(p, "<green>Home <yellow><n></yellow> deleted.", ph("n", n));
    }

    private void cmdHomes(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null) return;
        Set<String> h = homeNames(p);
        send(p, h.isEmpty() ? "<gray>You have no homes. Use /sethome." : "<gold>Homes: <yellow><list>",
                ph("list", String.join(", ", h)));
    }

    // ---- warps & spawn

    private void cmdSetWarp(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.warp.set")) return;
        if (a.length < 1 || !NAME.matcher(a[0]).matches()) {
            send(p, "<red>Usage: /setwarp <name> (letters, numbers, - and _)");
            return;
        }
        data.set("warps." + a[0].toLowerCase(Locale.ROOT), p.getLocation());
        saveData();
        send(p, "<green>Warp <yellow><n></yellow> set.", ph("n", a[0].toLowerCase(Locale.ROOT)));
    }

    private void cmdWarp(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 1) {
            cmdWarps(s);
            return;
        }
        String n = a[0].toLowerCase(Locale.ROOT);
        Location l = data.getLocation("warps." + n);
        if (l == null) {
            send(p, "<red>Warp <n> not found.", ph("n", n));
            return;
        }
        teleport(p, l, "warp " + n);
    }

    private void cmdDelWarp(CommandSender s, String[] a) {
        if (!perm(s, "allinone.warp.set")) return;
        if (a.length < 1 || !warpNames().contains(a[0].toLowerCase(Locale.ROOT))) {
            send(s, "<red>Usage: /delwarp <name> (warp must exist)");
            return;
        }
        data.set("warps." + a[0].toLowerCase(Locale.ROOT), null);
        saveData();
        send(s, "<green>Warp deleted.");
    }

    private void cmdWarps(CommandSender s) {
        Set<String> w = warpNames();
        send(s, w.isEmpty() ? "<gray>No warps set." : "<gold>Warps: <yellow><list>", ph("list", String.join(", ", w)));
    }

    private void cmdSetSpawn(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.spawn.set")) return;
        data.set("spawn", p.getLocation());
        saveData();
        send(p, "<green>Spawn set.");
    }

    private void cmdSpawn(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null) return;
        teleport(p, spawnLocation(), "spawn");
    }

    // ---- tpa / back

    private void cmdTpa(CommandSender s, String[] a, boolean here) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 1) {
            send(p, "<red>Usage: /<c> <player>", ph("c", here ? "tpahere" : "tpa"));
            return;
        }
        Player t = findPlayer(p, a[0]);
        if (t == null) return;
        if (t.equals(p)) {
            send(p, "<red>You can't send a request to yourself.");
            return;
        }
        long timeout = getConfig().getLong("tpa-timeout", 60) * 1000L;
        tpaRequests.put(t.getUniqueId(), new TpaRequest(p.getUniqueId(), here, System.currentTimeMillis() + timeout));
        send(p, "<green>Teleport request sent to <yellow><n></yellow>.", ph("n", t.getName()));
        send(t, "<yellow><n></yellow> wants to <what>. <green>/tpaccept</green> or <red>/tpdeny</red>.",
                ph("n", p.getName()), ph("what", here ? "teleport you to them" : "teleport to you"));
    }

    private void cmdTpAccept(CommandSender s, boolean accept) {
        Player target = requirePlayer(s);
        if (target == null) return;
        TpaRequest r = tpaRequests.remove(target.getUniqueId());
        if (r == null || r.expiresAt() < System.currentTimeMillis()) {
            send(target, "<red>You have no pending teleport requests.");
            return;
        }
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            send(target, "<red>That player is no longer online.");
            return;
        }
        if (!accept) {
            send(target, "<gray>Request denied.");
            send(from, "<red><n> denied your teleport request.", ph("n", target.getName()));
            return;
        }
        send(from, "<green><n> accepted your request.", ph("n", target.getName()));
        if (r.here()) teleport(target, from.getLocation(), from.getName());
        else teleport(from, target.getLocation(), target.getName());
    }

    private void cmdBack(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null) return;
        Location l = backLocations.get(p.getUniqueId());
        if (l == null) {
            send(p, "<red>No previous location.");
            return;
        }
        teleport(p, l, "your previous location");
    }

    // ---- economy

    private void cmdBalance(CommandSender s, String[] a) {
        if (a.length > 0) {
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(a[0]);
            if (!op.hasPlayedBefore() && !op.isOnline()) {
                send(s, "<red>Unknown player.");
                return;
            }
            send(s, "<gold><n>'s balance: <yellow><b>", ph("n", a[0]), ph("b", money(balance(op.getUniqueId()))));
            return;
        }
        Player p = requirePlayer(s);
        if (p == null) return;
        send(p, "<gold>Balance: <yellow><b>", ph("b", money(balance(p.getUniqueId()))));
    }

    private void cmdPay(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 2) {
            send(p, "<red>Usage: /pay <player> <amount>");
            return;
        }
        Player t = findPlayer(p, a[0]);
        if (t == null) return;
        Double amt = parseAmount(p, a[1]);
        if (amt == null) return;
        if (t.equals(p)) {
            send(p, "<red>You can't pay yourself.");
            return;
        }
        if (!economy.withdraw(p.getUniqueId(), amt)) {
            send(p, "<red>Insufficient funds.");
            return;
        }
        economy.deposit(t.getUniqueId(), amt);
        send(p, "<green>Paid <yellow><a></yellow> to <yellow><n></yellow>.", ph("a", money(amt)), ph("n", t.getName()));
        send(t, "<green>You received <yellow><a></yellow> from <yellow><n></yellow>.", ph("a", money(amt)), ph("n", p.getName()));
    }

    private void cmdBalTop(CommandSender s) {
        List<Map.Entry<UUID, Double>> list = economy.top(10);
        if (list.isEmpty()) {
            send(s, "<gray>No balances yet.");
            return;
        }
        send(s, "<gold>--- Top balances ---");
        int i = 1;
        for (Map.Entry<UUID, Double> e : list) {
            String n = Bukkit.getOfflinePlayer(e.getKey()).getName();
            send(s, "<yellow><i>. <white><n> <gray>- <green><b>",
                    ph("i", String.valueOf(i)), ph("n", n == null ? "Unknown" : n), ph("b", money(e.getValue())));
            i++;
        }
    }

    private void cmdEco(CommandSender s, String[] a) {
        if (!perm(s, "allinone.eco")) return;
        if (a.length < 3) {
            send(s, "<red>Usage: /eco <give|take|set> <player> <amount>");
            return;
        }
        Player t = findPlayer(s, a[1]);
        if (t == null) return;
        Double amt = parseAmount(s, a[2]);
        if (amt == null) return;
        UUID id = t.getUniqueId();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> setBalance(id, balance(id) + amt);
            case "take" -> setBalance(id, balance(id) - amt);
            case "set" -> setBalance(id, amt);
            default -> {
                send(s, "<red>Usage: /eco <give|take|set> <player> <amount>");
                return;
            }
        }
        send(s, "<green><n>'s balance is now <yellow><b></yellow>.", ph("n", t.getName()), ph("b", money(balance(id))));
    }

    // ---- kits

    private void cmdKit(CommandSender s, String[] a) {
        ConfigurationSection kits = getConfig().getConfigurationSection("kits");
        if (kits == null || kits.getKeys(false).isEmpty()) {
            send(s, "<gray>No kits configured.");
            return;
        }
        if (a.length < 1) {
            send(s, "<gold>Kits: <yellow><list>", ph("list", String.join(", ", kits.getKeys(false))));
            return;
        }
        Player p = requirePlayer(s);
        if (p == null) return;
        String key = a[0].toLowerCase(Locale.ROOT);
        ConfigurationSection kit = kits.getConfigurationSection(key);
        if (kit == null) {
            send(p, "<red>Kit not found.");
            return;
        }
        String permission = kit.getString("permission");
        if (permission != null && !p.hasPermission(permission)) {
            send(p, "<red>You don't have permission for that kit.");
            return;
        }
        long cooldownMs = kit.getLong("cooldown", 0) * 1000L;
        String cdPath = "kitcd." + p.getUniqueId() + "." + key;
        long last = data.getLong(cdPath, 0);
        long remaining = last + cooldownMs - System.currentTimeMillis();
        if (remaining > 0 && !p.hasPermission("allinone.bypass.kitcooldown")) {
            send(p, "<red>You can use this kit again in <t>.", ph("t", formatDuration(remaining)));
            return;
        }
        for (String line : kit.getStringList("items")) {
            String[] parts = line.split(":");
            Material m = Material.matchMaterial(parts[0].trim());
            if (m == null || m.isAir()) continue;
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack(m, amount));
            for (ItemStack extra : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), extra);
            }
        }
        data.set(cdPath, System.currentTimeMillis());
        saveData();
        send(p, "<green>Kit <yellow><n></yellow> received!", ph("n", key));
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        long h = sec / 3600, m = (sec % 3600) / 60, s2 = sec % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        sb.append(s2).append("s");
        return sb.toString();
    }

    // ---- moderation

    private void cmdMute(CommandSender s, String[] a) {
        if (!perm(s, "allinone.mute")) return;
        if (a.length < 1) {
            send(s, "<red>Usage: /mute <player> [minutes]");
            return;
        }
        Player t = findPlayer(s, a[0]);
        if (t == null) return;
        long expiry = -1;
        if (a.length > 1) {
            try {
                expiry = System.currentTimeMillis() + Long.parseLong(a[1]) * 60_000L;
            } catch (NumberFormatException e) {
                send(s, "<red>Invalid minutes.");
                return;
            }
        }
        mutes.put(t.getUniqueId(), expiry);
        saveData();
        send(s, "<green>Muted <yellow><n></yellow>.", ph("n", t.getName()));
        send(t, "<red>You have been muted.");
    }

    private void cmdUnmute(CommandSender s, String[] a) {
        if (!perm(s, "allinone.mute")) return;
        if (a.length < 1) {
            send(s, "<red>Usage: /unmute <player>");
            return;
        }
        Player t = findPlayer(s, a[0]);
        if (t == null) return;
        mutes.remove(t.getUniqueId());
        saveData();
        send(s, "<green>Unmuted <yellow><n></yellow>.", ph("n", t.getName()));
        send(t, "<green>You have been unmuted.");
    }

    private boolean isMuted(UUID id) {
        Long exp = mutes.get(id);
        if (exp == null) return false;
        if (exp != -1 && exp < System.currentTimeMillis()) {
            mutes.remove(id);
            return false;
        }
        return true;
    }

    // ---- player utilities

    private Player targetOrSelf(CommandSender s, String[] a, int idx, String permOthers) {
        if (a.length > idx) {
            if (!perm(s, permOthers)) return null;
            return findPlayer(s, a[idx]);
        }
        return requirePlayer(s);
    }

    private void cmdHeal(CommandSender s, String[] a) {
        if (!perm(s, "allinone.heal")) return;
        Player t = targetOrSelf(s, a, 0, "allinone.heal.others");
        if (t == null) return;
        AttributeInstance max = t.getAttribute(Attribute.MAX_HEALTH);
        t.setHealth(max != null ? max.getValue() : 20.0);
        t.setFoodLevel(20);
        t.setSaturation(20f);
        t.setFireTicks(0);
        send(t, "<green>You have been healed.");
    }

    private void cmdFeed(CommandSender s, String[] a) {
        if (!perm(s, "allinone.feed")) return;
        Player t = targetOrSelf(s, a, 0, "allinone.feed.others");
        if (t == null) return;
        t.setFoodLevel(20);
        t.setSaturation(20f);
        send(t, "<green>Hunger restored.");
    }

    private void cmdFly(CommandSender s, String[] a) {
        if (!perm(s, "allinone.fly")) return;
        Player t = targetOrSelf(s, a, 0, "allinone.fly.others");
        if (t == null) return;
        boolean on = !t.getAllowFlight();
        t.setAllowFlight(on);
        if (!on) t.setFlying(false);
        send(t, on ? "<green>Flight enabled." : "<yellow>Flight disabled.");
    }

    private void cmdGm(CommandSender s, String[] a) {
        if (!perm(s, "allinone.gamemode")) return;
        if (a.length < 1) {
            send(s, "<red>Usage: /gm <s|c|a|sp> [player]");
            return;
        }
        GameMode mode = switch (a[0].toLowerCase(Locale.ROOT)) {
            case "s", "survival", "0" -> GameMode.SURVIVAL;
            case "c", "creative", "1" -> GameMode.CREATIVE;
            case "a", "adventure", "2" -> GameMode.ADVENTURE;
            case "sp", "spectator", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
        if (mode == null) {
            send(s, "<red>Unknown gamemode.");
            return;
        }
        Player t = targetOrSelf(s, a, 1, "allinone.gamemode.others");
        if (t == null) return;
        t.setGameMode(mode);
        send(t, "<green>Gamemode set to <yellow><m></yellow>.", ph("m", mode.name().toLowerCase(Locale.ROOT)));
    }

    private void cmdGod(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.god")) return;
        if (!godMode.remove(p.getUniqueId())) {
            godMode.add(p.getUniqueId());
            send(p, "<green>God mode enabled.");
        } else {
            send(p, "<yellow>God mode disabled.");
        }
    }

    private void cmdVanish(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.vanish")) return;
        if (vanished.remove(p.getUniqueId())) {
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(this, p);
            send(p, "<yellow>You are now visible.");
        } else {
            vanished.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p) && !o.hasPermission("allinone.vanish.see")) o.hidePlayer(this, p);
            }
            send(p, "<green>You are now vanished.");
        }
    }

    private void cmdInvsee(CommandSender s, String[] a, boolean ender) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, ender ? "allinone.enderchest" : "allinone.invsee")) return;
        if (a.length < 1) {
            if (ender) {
                p.openInventory(p.getEnderChest());
            } else {
                send(p, "<red>Usage: /invsee <player>");
            }
            return;
        }
        Player t = findPlayer(p, a[0]);
        if (t == null) return;
        p.openInventory(ender ? t.getEnderChest() : t.getInventory());
    }

    private void cmdRepair(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.repair")) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && d.hasDamage()) {
            d.setDamage(0);
            item.setItemMeta(d);
            send(p, "<green>Item repaired.");
        } else {
            send(p, "<red>That item can't be repaired.");
        }
    }

    private void cmdHat(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.hat")) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            send(p, "<red>Hold an item first.");
            return;
        }
        ItemStack oldHelmet = p.getInventory().getHelmet();
        p.getInventory().setHelmet(hand.clone());
        p.getInventory().setItemInMainHand(oldHelmet == null ? new ItemStack(Material.AIR) : oldHelmet);
        send(p, "<green>Enjoy your new hat!");
    }

    private void cmdWorkbench(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.workbench")) return;
        p.openWorkbench(null, true);
    }

    private void cmdSpeed(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.speed")) return;
        if (a.length < 1) {
            send(p, "<red>Usage: /speed <1-10>");
            return;
        }
        int v;
        try {
            v = Integer.parseInt(a[0]);
        } catch (NumberFormatException e) {
            send(p, "<red>Invalid number.");
            return;
        }
        v = Math.max(1, Math.min(10, v));
        float f = v / 10f;
        if (p.isFlying()) p.setFlySpeed(f);
        else p.setWalkSpeed(Math.min(1f, f));
        send(p, "<green>Speed set to <yellow><v></yellow>.", ph("v", String.valueOf(v)));
    }

    // ---- world

    private void cmdTime(CommandSender s, long time, String label) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.time")) return;
        p.getWorld().setTime(time);
        send(p, "<green>Time set to <l>.", ph("l", label));
    }

    private void cmdWeather(CommandSender s, boolean rain) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.weather")) return;
        World w = p.getWorld();
        w.setStorm(rain);
        w.setThundering(false);
        send(p, "<green>Weather set to <l>.", ph("l", rain ? "rain" : "clear"));
    }

    private void cmdTop(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null || !perm(p, "allinone.top")) return;
        Location l = p.getLocation();
        int y = l.getWorld().getHighestBlockYAt(l) + 1;
        Location dest = new Location(l.getWorld(), l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch());
        doTeleport(p, dest, "the top");
    }

    // ---- chat

    private void cmdBroadcast(CommandSender s, String[] a) {
        if (!perm(s, "allinone.broadcast")) return;
        if (a.length < 1) {
            send(s, "<red>Usage: /broadcast <message>");
            return;
        }
        String text = String.join(" ", a);
        Bukkit.broadcast(mm.deserialize(getConfig().getString("broadcast-format",
                "<gold>[Broadcast]</gold> <white><message>"), Placeholder.unparsed("message", text)));
    }

    private void cmdMsg(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length < 2) {
            send(p, "<red>Usage: /msg <player> <message>");
            return;
        }
        Player t = findPlayer(p, a[0]);
        if (t == null) return;
        sendPrivate(p, t, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
    }

    private void cmdReply(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        UUID id = lastMessaged.get(p.getUniqueId());
        Player t = id == null ? null : Bukkit.getPlayer(id);
        if (t == null) {
            send(p, "<red>Nobody to reply to.");
            return;
        }
        if (a.length < 1) {
            send(p, "<red>Usage: /reply <message>");
            return;
        }
        sendPrivate(p, t, String.join(" ", a));
    }

    private void sendPrivate(Player from, Player to, String text) {
        if (isMuted(from.getUniqueId())) {
            send(from, "<red>You are muted.");
            return;
        }
        from.sendMessage(mm.deserialize("<gray>[<white>You</white> → <white><n></white>]</gray> <msg>",
                ph("n", to.getName()), ph("msg", text)));
        to.sendMessage(mm.deserialize("<gray>[<white><n></white> → <white>You</white>]</gray> <msg>",
                ph("n", from.getName()), ph("msg", text)));
        lastMessaged.put(from.getUniqueId(), to.getUniqueId());
        lastMessaged.put(to.getUniqueId(), from.getUniqueId());
    }

    private void cmdPing(CommandSender s, String[] a) {
        Player t = a.length > 0 ? findPlayer(s, a[0]) : requirePlayer(s);
        if (t == null) return;
        send(s, "<gold><n>'s ping: <yellow><p>ms", ph("n", t.getName()), ph("p", String.valueOf(t.getPing())));
    }

    private void cmdNear(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        double radius = 200;
        if (a.length > 0) {
            try {
                radius = Math.min(1000, Math.max(1, Double.parseDouble(a[0])));
            } catch (NumberFormatException ignored) {
            }
        }
        List<String> out = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || vanished.contains(o.getUniqueId())) continue;
            double d = o.getLocation().distance(p.getLocation());
            if (d <= radius) out.add(o.getName() + " (" + (int) d + "m)");
        }
        send(p, out.isEmpty() ? "<gray>Nobody nearby." : "<gold>Nearby: <yellow><list>", ph("list", String.join(", ", out)));
    }

    private void cmdShop(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length > 0) shop.openCategory(p, a[0], 0);
        else shop.openMain(p);
    }

    private void cmdSell(CommandSender s, String[] a) {
        Player p = requirePlayer(s);
        if (p == null) return;
        if (a.length > 0 && a[0].equalsIgnoreCase("all")) shop.sellAll(p);
        else shop.sellHand(p);
    }

    private void cmdScoreboard(CommandSender s) {
        Player p = requirePlayer(s);
        if (p == null) return;
        boolean on = scoreboards.toggle(p);
        send(p, on ? "<green>Scoreboard enabled." : "<yellow>Scoreboard hidden. Use /sb to show it again.");
    }

    private void cmdAllInOne(CommandSender s, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!perm(s, "allinone.admin")) return;
            reloadConfig();
            loadData();
            scoreboards.load(data);
            shop.reload();
            scoreboards.reload();
            send(s, "<green>AllInOne reloaded.");
            return;
        }
        send(s, "<gold>AllInOne <gray>v<v> - homes, warps, spawn, tpa, economy, kits and more.",
                ph("v", getPluginMeta().getVersion()));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        String cur = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

        switch (name) {
            case "home", "delhome" -> {
                if (args.length == 1 && sender instanceof Player p) out.addAll(homeNames(p));
            }
            case "warp", "delwarp" -> {
                if (args.length == 1) out.addAll(warpNames());
            }
            case "kit" -> {
                ConfigurationSection k = getConfig().getConfigurationSection("kits");
                if (args.length == 1 && k != null) out.addAll(k.getKeys(false));
            }
            case "gm" -> {
                if (args.length == 1) out.addAll(List.of("survival", "creative", "adventure", "spectator"));
                else if (args.length == 2) addPlayers(out);
            }
            case "eco" -> {
                if (args.length == 1) out.addAll(List.of("give", "take", "set"));
                else if (args.length == 2) addPlayers(out);
            }
            case "allinone" -> {
                if (args.length == 1) out.add("reload");
            }
            case "shop" -> {
                if (args.length == 1) out.addAll(shop.categoryIds());
            }
            case "sell" -> {
                if (args.length == 1) out.addAll(List.of("hand", "all"));
            }
            case "tpa", "tpahere", "pay", "msg", "mute", "unmute", "invsee", "enderchest",
                 "heal", "feed", "fly", "ping", "balance" -> {
                if (args.length == 1) addPlayers(out);
            }
            default -> {
            }
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }

    private void addPlayers(List<String> out) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!vanished.contains(p.getUniqueId())) out.add(p.getName());
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        updateVanishFor(p);
        economy.createAccount(p.getUniqueId());
        scoreboards.onJoin(p);
        String key = p.hasPlayedBefore() ? "join-message" : "first-join-message";
        String fmt = getConfig().getString(key, "");
        e.joinMessage(fmt.isEmpty() ? null : mm.deserialize(fmt, ph("player", p.getName())));
        if (vanished.contains(p.getUniqueId())) e.joinMessage(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String fmt = getConfig().getString("quit-message", "");
        e.quitMessage(fmt.isEmpty() || vanished.contains(p.getUniqueId()) ? null
                : mm.deserialize(fmt, ph("player", p.getName())));
        pendingTeleports.remove(p.getUniqueId());
        tpaRequests.remove(p.getUniqueId());
        scoreboards.onQuit(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (isMuted(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            send(e.getPlayer(), "<red>You are muted.");
            return;
        }
        if (!getConfig().getBoolean("chat.enabled", true)) return;
        String fmt = getConfig().getString("chat.format", "<player> » <message>");
        String rankMini = scoreboards.rank(e.getPlayer());
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) ->
                mm.deserialize(fmt,
                        Placeholder.parsed("rank", rankMini),
                        Placeholder.component("player", displayName),
                        Placeholder.component("message", message))));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (pendingTeleports.isEmpty() || !e.hasChangedBlock()) return;
        if (pendingTeleports.remove(e.getPlayer().getUniqueId()) != null) {
            send(e.getPlayer(), "<red>Teleport cancelled because you moved.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && godMode.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p.hasPermission("allinone.back.death")) {
            backLocations.put(p.getUniqueId(), p.getLocation().clone());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (getConfig().getBoolean("respawn-at-spawn", true)
                && !e.isBedSpawn() && !e.isAnchorSpawn()) {
            e.setRespawnLocation(spawnLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.COMMAND || c == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            backLocations.put(e.getPlayer().getUniqueId(), e.getFrom().clone());
        }
    }
}
