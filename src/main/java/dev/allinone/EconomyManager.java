package dev.allinone;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory balances (Vault may call from any thread). Persisted by AllInOne's data.yml. */
public final class EconomyManager {

    private final AllInOne plugin;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();

    public EconomyManager(AllInOne plugin) {
        this.plugin = plugin;
    }

    public void load(YamlConfiguration data) {
        balances.clear();
        ConfigurationSection s = data.getConfigurationSection("balances");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                balances.put(UUID.fromString(k), s.getDouble(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save(YamlConfiguration data) {
        data.set("balances", null);
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            data.set("balances." + e.getKey(), e.getValue());
        }
    }

    public double startingBalance() {
        return plugin.getConfig().getDouble("economy.starting-balance", 100.0);
    }

    public boolean hasAccount(UUID id) {
        return balances.containsKey(id);
    }

    public void createAccount(UUID id) {
        if (balances.putIfAbsent(id, startingBalance()) == null) plugin.markDirty();
    }

    public double get(UUID id) {
        return balances.getOrDefault(id, startingBalance());
    }

    public synchronized void set(UUID id, double amount) {
        balances.put(id, round(Math.max(0, amount)));
        plugin.markDirty();
    }

    public synchronized void deposit(UUID id, double amount) {
        balances.put(id, round(get(id) + amount));
        plugin.markDirty();
    }

    /** @return false if the player can't afford it. */
    public synchronized boolean withdraw(UUID id, double amount) {
        double cur = get(id);
        if (cur + 1e-9 < amount) return false;
        balances.put(id, round(Math.max(0, cur - amount)));
        plugin.markDirty();
        return true;
    }

    public boolean has(UUID id, double amount) {
        return get(id) + 1e-9 >= amount;
    }

    public List<Map.Entry<UUID, Double>> top(int n) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(balances.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.size() > n ? list.subList(0, n) : list;
    }

    public String symbol() {
        return plugin.getConfig().getString("economy.currency-symbol", "$");
    }

    public String format(double amount) {
        return symbol() + String.format(Locale.US, "%,.2f", amount);
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
