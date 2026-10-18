package dev.allinone;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Flicker-free animated sidebar + tab list + rank placeholders.
 * Every line is a team prefix on a unique invisible entry, so updates never flicker.
 */
public final class ScoreboardManager {

    private static final int MAX_LINES = 15;

    private static final class Board {
        final Scoreboard scoreboard;
        final Objective objective;
        final List<Team> teams = new ArrayList<>();
        final List<String> entries = new ArrayList<>();
        final List<Component> last = new ArrayList<>();
        Component lastTitle;

        Board(Scoreboard sb, Objective obj) {
            this.scoreboard = sb;
            this.objective = obj;
        }
    }

    private final AllInOne plugin;
    private final EconomyManager economy;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Board> boards = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>();
    private BukkitTask task;
    private long tick;

    public ScoreboardManager(AllInOne plugin, EconomyManager economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    // ------------------------------------------------------------------ persistence

    public void load(YamlConfiguration data) {
        disabled.clear();
        for (String s : data.getStringList("scoreboard-off")) {
            try {
                disabled.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save(YamlConfiguration data) {
        List<String> l = new ArrayList<>();
        for (UUID id : disabled) l.add(id.toString());
        data.set("scoreboard-off", l);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)
                && !plugin.getConfig().getBoolean("tab.enabled", true)) {
            return;
        }
        long interval = Math.max(2, plugin.getConfig().getLong("scoreboard.update-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tick += interval;
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 20L, interval);
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        boards.clear();
    }

    public void reload() {
        start();
    }

    public void onJoin(Player p) {
        update(p);
    }

    public void onQuit(Player p) {
        boards.remove(p.getUniqueId());
    }

    /** @return true if the scoreboard is now ON for the player. */
    public boolean toggle(Player p) {
        UUID id = p.getUniqueId();
        if (disabled.remove(id)) {
            plugin.markDirty();
            update(p);
            return true;
        }
        disabled.add(id);
        plugin.markDirty();
        boards.remove(id);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        return false;
    }

    // ------------------------------------------------------------------ ranks

    /** MiniMessage-formatted rank display for a player (first matching permission wins). */
    public String rank(Player p) {
        List<Map<?, ?>> ranks = plugin.getConfig().getMapList("ranks");
        for (Map<?, ?> m : ranks) {
            Object perm = m.get("permission");
            Object display = m.get("display");
            if (perm != null && display != null && p.hasPermission(perm.toString())) {
                return display.toString();
            }
        }
        return plugin.getConfig().getString("default-rank", "<gray>Member</gray>");
    }

    // ------------------------------------------------------------------ rendering

    private TagResolver resolvers(Player p) {
        Component rankComponent = mm.deserialize(rank(p));
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        String tpsColor = tps >= 18 ? "<green>" : tps >= 15 ? "<yellow>" : "<red>";
        int ping = p.getPing();
        String pingColor = ping <= 80 ? "<green>" : ping <= 160 ? "<yellow>" : "<red>";
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);

        ZoneId zone;
        try {
            zone = ZoneId.of(plugin.getConfig().getString("scoreboard.timezone", ZoneId.systemDefault().getId()));
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);

        int online = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!plugin.isVanished(o)) online++;
        }

        return TagResolver.resolver(
                Placeholder.unparsed("player", p.getName()),
                Placeholder.component("displayname", p.displayName()),
                Placeholder.component("rank", rankComponent),
                Placeholder.unparsed("balance", economy.format(economy.get(p.getUniqueId()))),
                Placeholder.unparsed("online", String.valueOf(online)),
                Placeholder.unparsed("max", String.valueOf(Bukkit.getMaxPlayers())),
                Placeholder.parsed("ping", pingColor + ping + "ms"),
                Placeholder.parsed("tps", tpsColor + String.format(Locale.US, "%.1f", tps)),
                Placeholder.unparsed("world", p.getWorld().getName()),
                Placeholder.unparsed("x", String.valueOf(p.getLocation().getBlockX())),
                Placeholder.unparsed("y", String.valueOf(p.getLocation().getBlockY())),
                Placeholder.unparsed("z", String.valueOf(p.getLocation().getBlockZ())),
                Placeholder.unparsed("kills", String.valueOf(kills)),
                Placeholder.unparsed("deaths", String.valueOf(deaths)),
                Placeholder.unparsed("kdr", String.format(Locale.US, "%.2f", kills / (double) Math.max(1, deaths))),
                Placeholder.unparsed("time", now.format(DateTimeFormatter.ofPattern("HH:mm"))),
                Placeholder.unparsed("date", now.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))),
                Placeholder.unparsed("server", plugin.getConfig().getString("scoreboard.server-name", "My Server"))
        );
    }

    private void update(Player p) {
        UUID id = p.getUniqueId();
        TagResolver r = resolvers(p);

        if (plugin.getConfig().getBoolean("tab.enabled", true)) {
            updateTab(p, r);
        }

        boolean wantBoard = plugin.getConfig().getBoolean("scoreboard.enabled", true) && !disabled.contains(id);
        if (!wantBoard) {
            if (boards.remove(id) != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            return;
        }

        List<String> lines = plugin.getConfig().getStringList("scoreboard.lines");
        if (lines.size() > MAX_LINES) lines = lines.subList(0, MAX_LINES);

        Board b = boards.get(id);
        if (b == null || b.teams.size() != lines.size()) {
            b = build(p, lines.size());
            boards.put(id, b);
            p.setScoreboard(b.scoreboard);
        }

        // animated title
        List<String> frames = plugin.getConfig().getStringList("scoreboard.title");
        if (frames.isEmpty()) frames = List.of("<gold><bold>Server");
        long frameTicks = Math.max(2, plugin.getConfig().getLong("scoreboard.title-frame-ticks", 6));
        String frame = frames.get((int) ((tick / frameTicks) % frames.size()));
        Component title = mm.deserialize(frame, r);
        if (!title.equals(b.lastTitle)) {
            b.objective.displayName(title);
            b.lastTitle = title;
        }

        for (int i = 0; i < lines.size(); i++) {
            Component line = mm.deserialize(lines.get(i), r);
            if (!line.equals(b.last.get(i))) {
                b.teams.get(i).prefix(line);
                b.last.set(i, line);
            }
        }
    }

    private Board build(Player p, int lineCount) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("aio", "dummy", Component.text("AllInOne"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            obj.numberFormat(NumberFormat.blank()); // hide the red score numbers
        } catch (Throwable ignored) {
        }
        Board b = new Board(sb, obj);
        for (int i = 0; i < lineCount; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            Team t = sb.registerNewTeam("l" + i);
            t.addEntry(entry);
            obj.getScore(entry).setScore(lineCount - i);
            b.teams.add(t);
            b.entries.add(entry);
            b.last.add(null);
        }
        return b;
    }

    private void updateTab(Player p, TagResolver r) {
        List<String> header = plugin.getConfig().getStringList("tab.header");
        List<String> footer = plugin.getConfig().getStringList("tab.footer");
        p.sendPlayerListHeaderAndFooter(join(header, r), join(footer, r));
        String fmt = plugin.getConfig().getString("tab.name-format", "");
        if (!fmt.isEmpty()) {
            p.playerListName(mm.deserialize(fmt, r));
        }
    }

    private Component join(List<String> lines, TagResolver r) {
        List<Component> comps = new ArrayList<>();
        for (String s : lines) comps.add(mm.deserialize(s, r));
        return Component.join(JoinConfiguration.newlines(), comps);
    }
}
