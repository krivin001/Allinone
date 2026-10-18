package dev.allinone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paginated GUI shop. Left click = buy, right click = sell. Shift = bulk. */
public final class ShopManager implements Listener {

    private static final int[] ITEM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int[] CATEGORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25};

    private record ShopItem(Material material, double buy, double sell) {}

    private static final class Category {
        String id;
        String title;
        Material icon;
        List<ShopItem> items = new ArrayList<>();
    }

    private static final class ShopHolder implements InventoryHolder {
        final Category category; // null = main menu
        final int page;
        Inventory inventory;

        ShopHolder(Category category, int page) {
            this.category = category;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final AllInOne plugin;
    private final EconomyManager economy;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<Material, Double> sellPrices = new HashMap<>();
    private String mainTitle = "<gold><bold>Shop";

    public ShopManager(AllInOne plugin, EconomyManager economy) {
        this.plugin = plugin;
        this.economy = economy;
        reload();
    }

    // ------------------------------------------------------------------ loading

    public void reload() {
        categories.clear();
        sellPrices.clear();
        File f = new File(plugin.getDataFolder(), "shop.yml");
        if (!f.exists()) plugin.saveResource("shop.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        mainTitle = y.getString("main-title", mainTitle);
        ConfigurationSection cs = y.getConfigurationSection("categories");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            Category cat = new Category();
            cat.id = id.toLowerCase();
            cat.title = c.getString("title", id);
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            cat.icon = icon == null ? Material.CHEST : icon;
            for (String line : c.getStringList("items")) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3) continue;
                Material m = Material.matchMaterial(p[0]);
                if (m == null || m.isAir() || !m.isItem()) {
                    plugin.getLogger().warning("shop.yml: unknown item '" + p[0] + "' in " + id);
                    continue;
                }
                try {
                    double buy = Double.parseDouble(p[1]);
                    double sell = Double.parseDouble(p[2]);
                    cat.items.add(new ShopItem(m, buy, sell));
                    if (sell >= 0) sellPrices.merge(m, sell, Math::max);
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("shop.yml: bad price in line '" + line + "'");
                }
            }
            categories.put(cat.id, cat);
        }
    }

    public List<String> categoryIds() {
        return new ArrayList<>(categories.keySet());
    }

    // ------------------------------------------------------------------ helpers

    private Component c(String mini, TagResolver... r) {
        return mm.deserialize(mini, r).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private void msg(Player p, String mini, TagResolver... r) {
        p.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix", "") + mini, r));
    }

    private static TagResolver ph(String k, String v) {
        return Placeholder.unparsed(k, v);
    }

    private ItemStack filler() {
        ItemStack i = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = i.getItemMeta();
        m.displayName(Component.empty());
        i.setItemMeta(m);
        return i;
    }

    private ItemStack named(Material mat, String name, String... lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.displayName(c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        m.lore(l);
        i.setItemMeta(m);
        return i;
    }

    private void sound(Player p, Sound s) {
        p.playSound(p.getLocation(), s, 0.7f, 1f);
    }

    // ------------------------------------------------------------------ GUI

    public void openMain(Player p) {
        if (categories.isEmpty()) {
            msg(p, "<red>The shop is empty. Edit shop.yml.");
            return;
        }
        ShopHolder h = new ShopHolder(null, 0);
        Inventory inv = Bukkit.createInventory(h, 36, mm.deserialize(mainTitle));
        h.inventory = inv;
        for (int i = 0; i < 36; i++) inv.setItem(i, filler());
        int idx = 0;
        for (Category cat : categories.values()) {
            if (idx >= CATEGORY_SLOTS.length) break;
            inv.setItem(CATEGORY_SLOTS[idx++], named(cat.icon, cat.title,
                    "<gray>" + cat.items.size() + " items", "", "<yellow>Click to open"));
        }
        inv.setItem(31, named(Material.GOLD_INGOT, "<gold>Your balance",
                "<yellow>" + economy.format(economy.get(p.getUniqueId()))));
        p.openInventory(inv);
        sound(p, Sound.UI_BUTTON_CLICK);
    }

    public void openCategory(Player p, String id, int page) {
        Category cat = categories.get(id.toLowerCase());
        if (cat == null) {
            msg(p, "<red>Unknown category. Available: <list>", ph("list", String.join(", ", categories.keySet())));
            return;
        }
        int perPage = ITEM_SLOTS.length;
        int pages = Math.max(1, (cat.items.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        ShopHolder h = new ShopHolder(cat, page);
        Inventory inv = Bukkit.createInventory(h, 54, mm.deserialize(cat.title + " <dark_gray>(" + (page + 1) + "/" + pages + ")"));
        h.inventory = inv;
        for (int i = 0; i < 54; i++) inv.setItem(i, filler());

        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < cat.items.size(); i++) {
            inv.setItem(ITEM_SLOTS[i], displayItem(cat.items.get(start + i)));
        }
        if (page > 0) inv.setItem(45, named(Material.ARROW, "<yellow>« Previous page"));
        inv.setItem(49, named(Material.BARRIER, "<red>Back to categories"));
        inv.setItem(4, named(Material.GOLD_INGOT, "<gold>Your balance",
                "<yellow>" + economy.format(economy.get(p.getUniqueId()))));
        if (page < pages - 1) inv.setItem(53, named(Material.ARROW, "<yellow>Next page »"));
        p.openInventory(inv);
    }

    private ItemStack displayItem(ShopItem it) {
        ItemStack stack = new ItemStack(it.material());
        ItemMeta m = stack.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(c(it.buy() >= 0
                ? "<gray>Buy: <green>" + economy.format(it.buy())
                : "<gray>Buy: <red>not for sale"));
        lore.add(c(it.sell() >= 0
                ? "<gray>Sell: <gold>" + economy.format(it.sell())
                : "<gray>Sell: <red>can't be sold"));
        lore.add(Component.empty());
        lore.add(c("<yellow>Left-click <gray>buy 1   <yellow>Shift+Left <gray>buy 64"));
        lore.add(c("<yellow>Right-click <gray>sell 1   <yellow>Shift+Right <gray>sell all"));
        m.lore(lore);
        stack.setItemMeta(m);
        return stack;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();

        if (h.category == null) {
            for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
                if (CATEGORY_SLOTS[i] == slot) {
                    List<Category> list = new ArrayList<>(categories.values());
                    if (i < list.size()) {
                        sound(p, Sound.UI_BUTTON_CLICK);
                        openCategory(p, list.get(i).id, 0);
                    }
                    return;
                }
            }
            return;
        }

        if (slot == 45 && h.page > 0) {
            openCategory(p, h.category.id, h.page - 1);
            return;
        }
        if (slot == 53) {
            openCategory(p, h.category.id, h.page + 1);
            return;
        }
        if (slot == 49) {
            openMain(p);
            return;
        }
        for (int i = 0; i < ITEM_SLOTS.length; i++) {
            if (ITEM_SLOTS[i] != slot) continue;
            int index = h.page * ITEM_SLOTS.length + i;
            if (index >= h.category.items.size()) return;
            ShopItem item = h.category.items.get(index);
            switch (e.getClick()) {
                case LEFT -> buy(p, item, 1);
                case SHIFT_LEFT -> buy(p, item, 64);
                case RIGHT -> sell(p, item, false);
                case SHIFT_RIGHT -> sell(p, item, true);
                default -> {
                }
            }
            // refresh balance display
            Inventory top = e.getView().getTopInventory();
            top.setItem(4, named(Material.GOLD_INGOT, "<gold>Your balance",
                    "<yellow>" + economy.format(economy.get(p.getUniqueId()))));
            return;
        }
    }

    // ------------------------------------------------------------------ buy / sell

    private void buy(Player p, ShopItem it, int amount) {
        if (it.buy() < 0) {
            msg(p, "<red>That item isn't for sale.");
            sound(p, Sound.ENTITY_VILLAGER_NO);
            return;
        }
        double cost = EconomyManager.round(it.buy() * amount);
        if (!economy.withdraw(p.getUniqueId(), cost)) {
            msg(p, "<red>You need <c> to buy that.", ph("c", economy.format(cost)));
            sound(p, Sound.ENTITY_VILLAGER_NO);
            return;
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(it.material(), amount));
        int notGiven = 0;
        for (ItemStack s : left.values()) notGiven += s.getAmount();
        if (notGiven > 0) {
            economy.deposit(p.getUniqueId(), EconomyManager.round(it.buy() * notGiven));
        }
        int given = amount - notGiven;
        if (given <= 0) {
            msg(p, "<red>Your inventory is full.");
            sound(p, Sound.ENTITY_VILLAGER_NO);
            return;
        }
        msg(p, "<green>Bought <yellow><n>x <item></yellow> for <yellow><c></yellow>.",
                ph("n", String.valueOf(given)), ph("item", pretty(it.material())),
                ph("c", economy.format(EconomyManager.round(it.buy() * given))));
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    private void sell(Player p, ShopItem it, boolean all) {
        if (it.sell() < 0) {
            msg(p, "<red>That item can't be sold.");
            sound(p, Sound.ENTITY_VILLAGER_NO);
            return;
        }
        int have = count(p, it.material());
        int amount = all ? have : Math.min(1, have);
        if (amount <= 0) {
            msg(p, "<red>You don't have any <item>.", ph("item", pretty(it.material())));
            sound(p, Sound.ENTITY_VILLAGER_NO);
            return;
        }
        int removed = remove(p, it.material(), amount);
        double total = EconomyManager.round(it.sell() * removed);
        economy.deposit(p.getUniqueId(), total);
        msg(p, "<green>Sold <yellow><n>x <item></yellow> for <yellow><c></yellow>.",
                ph("n", String.valueOf(removed)), ph("item", pretty(it.material())), ph("c", economy.format(total)));
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    private static boolean plain(ItemStack s, Material m) {
        return s != null && s.getType() == m && !s.hasItemMeta();
    }

    private int count(Player p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (plain(s, m)) n += s.getAmount();
        }
        return n;
    }

    private int remove(Player p, Material m, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (!plain(s, m)) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take == s.getAmount()) contents[i] = null;
            else s.setAmount(s.getAmount() - take);
        }
        p.getInventory().setStorageContents(contents);
        return amount - left;
    }

    private static String pretty(Material m) {
        String[] parts = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ commands

    public void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Double price = sellPrices.get(hand.getType());
        if (hand.getType().isAir() || price == null || hand.hasItemMeta()) {
            msg(p, "<red>You can't sell the item in your hand.");
            return;
        }
        int amount = hand.getAmount();
        p.getInventory().setItemInMainHand(null);
        double total = EconomyManager.round(price * amount);
        economy.deposit(p.getUniqueId(), total);
        msg(p, "<green>Sold <yellow><n>x <item></yellow> for <yellow><c></yellow>.",
                ph("n", String.valueOf(amount)), ph("item", pretty(hand.getType())), ph("c", economy.format(total)));
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    public void sellAll(Player p) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        double total = 0;
        int items = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.hasItemMeta()) continue;
            Double price = sellPrices.get(s.getType());
            if (price == null) continue;
            total += price * s.getAmount();
            items += s.getAmount();
            contents[i] = null;
        }
        if (items == 0) {
            msg(p, "<red>You have nothing sellable in your inventory.");
            sound(p, Sound.ENTITY_VILLAGER_NO);
            return;
        }
        p.getInventory().setStorageContents(contents);
        total = EconomyManager.round(total);
        economy.deposit(p.getUniqueId(), total);
        msg(p, "<green>Sold <yellow><n></yellow> items for <yellow><c></yellow>.",
                ph("n", String.valueOf(items)), ph("c", economy.format(total)));
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
    }

    public void worth(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            msg(p, "<red>Hold an item first.");
            return;
        }
        Double price = sellPrices.get(hand.getType());
        if (price == null) {
            msg(p, "<gray><item> can't be sold.", ph("item", pretty(hand.getType())));
            return;
        }
        msg(p, "<gold><item> <gray>sells for <yellow><c></yellow> each (<yellow><t></yellow> for your stack).",
                ph("item", pretty(hand.getType())), ph("c", economy.format(price)),
                ph("t", economy.format(EconomyManager.round(price * hand.getAmount()))));
    }
}
