package dev.allinone;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GSit-style poses without ProtocolLib:
 *  - /sit   : invisible armor stand seat (also right-click stairs/slabs with an empty hand)
 *  - /lay   : forced sleep on a client-side fake bed
 *  - /crawl : client-side barrier above the head forces the crawling pose
 */
public final class SitManager implements Listener {

    private static final String SEAT_TAG = "allinone_seat";

    private final AllInOne plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    private final Map<UUID, Location> seatReturn = new HashMap<>();
    private final Map<UUID, Location[]> beds = new HashMap<>();
    private final Set<UUID> crawlers = new HashSet<>();
    private final Map<UUID, Location> sentFor = new HashMap<>();

    public SitManager(AllInOne plugin) {
        this.plugin = plugin;
        removeLeftoverSeats();
        // housekeeping: remove seats whose rider left or died
        Bukkit.getScheduler().runTaskTimer(plugin, this::housekeeping, 20L, 10L);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("sit.enabled", true);
    }

    private void msg(Player p, String mini) {
        p.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix", "") + mini));
    }

    private void removeLeftoverSeats() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                if (e.getScoreboardTags().contains(SEAT_TAG)) e.remove();
            }
        }
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            stopAll(p, false);
        }
        for (ArmorStand a : seats.values()) a.remove();
        seats.clear();
    }

    private void housekeeping() {
        Iterator<Map.Entry<UUID, ArmorStand>> it = seats.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ArmorStand> e = it.next();
            ArmorStand a = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || a.isDead() || a.getPassengers().isEmpty()) {
                a.remove();
                it.remove();
                Location back = seatReturn.remove(e.getKey());
                if (p != null && p.isOnline() && back != null) p.teleportAsync(back);
            }
        }
        Iterator<Map.Entry<UUID, Location[]>> bi = beds.entrySet().iterator();
        while (bi.hasNext()) {
            Map.Entry<UUID, Location[]> e = bi.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || !p.isSleeping()) {
                bi.remove();
                restoreBed(e.getValue());
                if (p != null && p.isOnline()) p.setSleepingIgnored(false);
            }
        }
    }

    /** Stops any pose the player is in. */
    public void stopAll(Player p) {
        stopAll(p, true);
    }

    private void stopAll(Player p, boolean teleportBack) {
        UUID id = p.getUniqueId();
        ArmorStand a = seats.remove(id);
        if (a != null) {
            a.remove();
            Location back = seatReturn.remove(id);
            if (back != null && teleportBack && p.isOnline()) p.teleportAsync(back);
        }
        Location[] bed = beds.remove(id);
        if (bed != null) {
            if (p.isSleeping()) p.wakeup(false);
            p.setSleepingIgnored(false);
            restoreBed(bed);
        }
        stopCrawl(p);
    }

    private boolean posing(Player p) {
        UUID id = p.getUniqueId();
        return seats.containsKey(id) || beds.containsKey(id) || crawlers.contains(id);
    }

    // ------------------------------------------------------------------ sit

    public void toggleSit(Player p) {
        if (!enabled()) {
            msg(p, "<red>Sitting is disabled.");
            return;
        }
        if (seats.containsKey(p.getUniqueId())) {
            stopAll(p);
            return;
        }
        if (p.isInsideVehicle() || posing(p)) {
            msg(p, "<red>You can't sit right now.");
            return;
        }
        Location below = p.getLocation().clone().subtract(0, 0.2, 0);
        if (below.getBlock().isPassable()) {
            msg(p, "<red>You need to be standing on something to sit.");
            return;
        }
        sitAt(p, p.getLocation().clone(), p.getLocation().clone());
    }

    private void sitAt(Player p, Location seat, Location returnTo) {
        double yOffset = plugin.getConfig().getDouble("sit.y-offset", 0.3);
        Location l = seat.clone();
        l.setY(l.getY() + yOffset);
        l.setYaw(p.getLocation().getYaw());
        ArmorStand stand = l.getWorld().spawn(l, ArmorStand.class, s -> {
            s.setVisible(false);
            s.setGravity(false);
            s.setMarker(true);
            s.setSmall(true);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setPersistent(false);
            s.addScoreboardTag(SEAT_TAG);
        });
        stand.addPassenger(p);
        seats.put(p.getUniqueId(), stand);
        seatReturn.put(p.getUniqueId(), returnTo);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("sit.right-click-stairs", true)) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || p.isInsideVehicle() || posing(p)) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        BlockData data = b.getBlockData();
        boolean ok = false;
        if (data instanceof Stairs st) ok = st.getHalf() == Bisected.Half.BOTTOM;
        else if (data instanceof Slab sl) ok = sl.getType() == Slab.Type.BOTTOM;
        if (!ok || !p.hasPermission("allinone.sit")) return;
        if (!b.getRelative(BlockFace.UP).isPassable()) return;
        if (p.getLocation().distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > 9) return;

        Location seat = b.getLocation().add(0.5, 0.5, 0.5);
        for (ArmorStand s : seats.values()) {
            if (s.getWorld().equals(seat.getWorld()) && s.getLocation().distanceSquared(seat) < 1.5) {
                return; // occupied
            }
        }
        e.setCancelled(true);
        Location back = b.getLocation().add(0.5, 1.0, 0.5);
        back.setYaw(p.getLocation().getYaw());
        sitAt(p, seat, back);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        if (seats.containsKey(p.getUniqueId())) {
            // vanilla dismounts on sneak; make sure the seat goes away at once
            Bukkit.getScheduler().runTask(plugin, () -> stopAll(p));
        }
    }

    // ------------------------------------------------------------------ lay

    public void toggleLay(Player p) {
        if (!enabled()) {
            msg(p, "<red>Posing is disabled.");
            return;
        }
        if (beds.containsKey(p.getUniqueId())) {
            stopAll(p);
            return;
        }
        if (p.isInsideVehicle() || posing(p)) {
            msg(p, "<red>You can't lay down right now.");
            return;
        }
        Block foot = p.getLocation().getBlock();
        BlockFace facing = yawToFace(p.getLocation().getYaw());
        Block head = foot.getRelative(facing);
        if (!foot.isPassable() || !head.isPassable()
                || foot.getRelative(BlockFace.DOWN).isPassable()) {
            msg(p, "<red>Not enough flat space here to lay down.");
            return;
        }

        Bed footData = (Bed) Material.WHITE_BED.createBlockData();
        footData.setPart(Bed.Part.FOOT);
        footData.setFacing(facing);
        Bed headData = (Bed) Material.WHITE_BED.createBlockData();
        headData.setPart(Bed.Part.HEAD);
        headData.setFacing(facing);

        Location footLoc = foot.getLocation();
        Location headLoc = head.getLocation();
        for (Player viewer : p.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(footLoc) < 64 * 64) {
                viewer.sendBlockChange(footLoc, footData);
                viewer.sendBlockChange(headLoc, headData);
            }
        }
        beds.put(p.getUniqueId(), new Location[]{footLoc, headLoc});
        p.setSleepingIgnored(true); // never counts toward skipping the night
        if (!p.sleep(footLoc, true)) {
            beds.remove(p.getUniqueId());
            restoreBed(new Location[]{footLoc, headLoc});
            p.setSleepingIgnored(false);
            msg(p, "<red>Couldn't lay down here.");
        }
    }

    private void restoreBed(Location[] locs) {
        for (Location l : locs) {
            BlockData real = l.getBlock().getBlockData();
            for (Player viewer : l.getWorld().getPlayers()) {
                if (viewer.getLocation().distanceSquared(l) < 80 * 80) viewer.sendBlockChange(l, real);
            }
        }
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent e) {
        Player p = e.getPlayer();
        Location[] bed = beds.remove(p.getUniqueId());
        if (bed != null) {
            p.setSleepingIgnored(false);
            Bukkit.getScheduler().runTask(plugin, () -> restoreBed(bed));
        }
    }

    private static BlockFace yawToFace(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 315 || y < 45) return BlockFace.SOUTH;
        if (y < 135) return BlockFace.WEST;
        if (y < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    // ------------------------------------------------------------------ crawl

    public void toggleCrawl(Player p) {
        if (!enabled()) {
            msg(p, "<red>Posing is disabled.");
            return;
        }
        if (crawlers.contains(p.getUniqueId())) {
            stopCrawl(p);
            msg(p, "<yellow>You stopped crawling.");
            return;
        }
        if (p.isInsideVehicle() || posing(p)) {
            msg(p, "<red>You can't crawl right now.");
            return;
        }
        crawlers.add(p.getUniqueId());
        updateCrawl(p, p.getLocation());
        msg(p, "<green>You are crawling. Use /crawl again to stand up.");
    }

    /** Puts a client-side barrier in the block above the player's feet, which forces the crawl pose. */
    private void updateCrawl(Player p, Location at) {
        Location head = at.getBlock().getRelative(BlockFace.UP).getLocation();
        Location prev = sentFor.get(p.getUniqueId());
        if (head.equals(prev)) return;
        if (prev != null) {
            p.sendBlockChange(prev, prev.getBlock().getBlockData());
            sentFor.remove(p.getUniqueId());
        }
        if (head.getBlock().isPassable()) {
            p.sendBlockChange(head, Material.BARRIER.createBlockData());
            sentFor.put(p.getUniqueId(), head);
        }
    }

    private void stopCrawl(Player p) {
        crawlers.remove(p.getUniqueId());
        Location sent = sentFor.remove(p.getUniqueId());
        if (sent != null && p.isOnline()) {
            p.sendBlockChange(sent, sent.getBlock().getBlockData());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (crawlers.isEmpty() || !e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        if (crawlers.contains(p.getUniqueId())) updateCrawl(p, e.getTo());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (crawlers.contains(p.getUniqueId())) stopCrawl(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stopAll(e.getPlayer(), false);
    }
}
