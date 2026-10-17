package dev.allinone;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Exposes AllInOne's economy to Vault, so plugins like EconomyShopGUI, ChestShop, etc. work.
 * This class is only loaded when Vault is installed.
 */
public final class VaultHook implements Economy {

    private final AllInOne plugin;
    private final EconomyManager eco;

    private VaultHook(AllInOne plugin, EconomyManager eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    public static void register(AllInOne plugin, EconomyManager eco) {
        plugin.getServer().getServicesManager().register(
                Economy.class, new VaultHook(plugin, eco), plugin, ServicePriority.Highest);
        plugin.getLogger().info("Registered as Vault economy provider.");
    }

    @SuppressWarnings("deprecation")
    private static UUID id(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private EconomyResponse ok(double amount, UUID id) {
        return new EconomyResponse(amount, eco.get(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse fail(double amount, UUID id, String msg) {
        return new EconomyResponse(amount, eco.get(id), EconomyResponse.ResponseType.FAILURE, msg);
    }

    private EconomyResponse withdraw(UUID id, double amount) {
        if (amount < 0) return fail(0, id, "Cannot withdraw negative funds");
        if (!eco.withdraw(id, amount)) return fail(0, id, "Insufficient funds");
        return ok(amount, id);
    }

    private EconomyResponse deposit(UUID id, double amount) {
        if (amount < 0) return fail(0, id, "Cannot deposit negative funds");
        eco.deposit(id, amount);
        return ok(amount, id);
    }

    private EconomyResponse noBank() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    // ---- info
    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "AllInOne"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return eco.format(amount); }
    @Override public String currencyNamePlural() { return plugin.getConfig().getString("economy.currency-name-plural", "Dollars"); }
    @Override public String currencyNameSingular() { return plugin.getConfig().getString("economy.currency-name-singular", "Dollar"); }

    // ---- accounts
    @Override public boolean hasAccount(String name) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String name, String world) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return true; }

    @Override public boolean createPlayerAccount(String name) { eco.createAccount(id(name)); return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { eco.createAccount(player.getUniqueId()); return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return createPlayerAccount(player); }

    // ---- balances
    @Override public double getBalance(String name) { return eco.get(id(name)); }
    @Override public double getBalance(OfflinePlayer player) { return eco.get(player.getUniqueId()); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String name, double amount) { return eco.has(id(name), amount); }
    @Override public boolean has(OfflinePlayer player, double amount) { return eco.has(player.getUniqueId(), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(id(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return withdraw(player.getUniqueId(), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(id(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return deposit(player.getUniqueId(), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }

    // ---- banks (unsupported)
    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
