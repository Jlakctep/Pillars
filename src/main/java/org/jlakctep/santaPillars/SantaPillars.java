package org.jlakctep.santaPillars;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import org.jlakctep.santaPillars.command.PillarsCommand;
import org.jlakctep.santaPillars.game.ArenaManager;
import org.jlakctep.santaPillars.item.ItemPool;
import org.jlakctep.santaPillars.listener.GameListener;
import org.jlakctep.santaPillars.npc.NPCManager;
import org.jlakctep.santaPillars.util.Msg;

import java.io.File;
 

public final class SantaPillars extends JavaPlugin {

    private ArenaManager arenaManager;
    private NPCManager npcManager;
    private ItemPool itemPool;
    private Object economy; // Vault Economy provider via reflection to avoid hard dependency

    private File arenasFile, npcsFile;
    private YamlConfiguration arenasCfg, npcsCfg;

    private File worldsDir;

    @Override
    public void onEnable() {
        // Ресурсы
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("arenas.yml", false);
        saveResource("npcs.yml", false);

        // Конфиги
        arenasFile = new File(getDataFolder(), "arenas.yml");
        npcsFile = new File(getDataFolder(), "npcs.yml");
        arenasCfg = YamlConfiguration.loadConfiguration(arenasFile);
        npcsCfg = YamlConfiguration.loadConfiguration(npcsFile);

        // Папка бэкапов миров
        worldsDir = new File(getDataFolder(), "worlds");
        if (!worldsDir.exists()) worldsDir.mkdirs();

        // Сервисы
        Msg.load(this);
        itemPool = new ItemPool(this);

        arenaManager = new ArenaManager(this);
        arenaManager.load(arenasCfg);

        npcManager = new NPCManager(this);
        npcManager.loadAndSpawn(npcsCfg);

        // Регистрация команды (Paper API)
        PillarsCommand delegate = new PillarsCommand(this);
        Command pillars = new Command("pillars") {
            {
                setDescription("Pillars admin/player command");
                setUsage("/pillars");
                setAliases(java.util.List.of("pof", "santapillars"));
            }
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return delegate.onCommand(sender, this, label, args);
            }
            @Override
            public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return delegate.onTabComplete(sender, this, alias, args);
            }
        };
        Bukkit.getCommandMap().register("santapillars", pillars);

        // Листенеры
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);

        // Экономика (Vault)
        setupEconomy();

        getLogger().info("SantaPillars enabled (Paper)");
    }

    @Override
    public void onDisable() {
        if (arenaManager != null) arenaManager.shutdown();
        if (npcManager != null) npcManager.despawnAll();
        saveArenas();
        saveNPCs();
        getLogger().info("SantaPillars disabled");
    }

    public ArenaManager arenas() { return arenaManager; }
    public NPCManager npcs() { return npcManager; }
    public ItemPool items() { return itemPool; }
    public File worldsDir() { return worldsDir; }
    public boolean depositMoney(Player player, double amount) {
        if (economy == null || player == null || amount <= 0.0) return false;
        try {
            // Prefer OfflinePlayer overload
            try {
                java.lang.reflect.Method m = economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class);
                Object res = m.invoke(economy, (org.bukkit.OfflinePlayer) player, amount);
                return res != null;
            } catch (NoSuchMethodException ignored) {}
            try {
                java.lang.reflect.Method m2 = economy.getClass().getMethod("depositPlayer", Player.class, double.class);
                Object res = m2.invoke(economy, player, amount);
                return res != null;
            } catch (NoSuchMethodException ignored) {}
            try {
                java.lang.reflect.Method m3 = economy.getClass().getMethod("depositPlayer", String.class, double.class);
                Object res = m3.invoke(economy, player.getName(), amount);
                return res != null;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) {
            getLogger().warning("Vault deposit failed: " + t.getMessage());
        }
        return false;
    }

    public void saveArenas() {
        if (arenaManager == null) return;
        arenaManager.save(arenasCfg);
        try { arenasCfg.save(arenasFile); } catch (Exception e) { e.printStackTrace(); }
    }
    public void saveNPCs() {
        if (npcManager == null) return;
        npcManager.save(npcsCfg);
        try { npcsCfg.save(npcsFile); } catch (Exception e) { e.printStackTrace(); }
    }

    public Location lobbySpawn() {
        var c = getConfig();
        World w = Bukkit.getWorld(c.getString("lobby.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        return new Location(
                w,
                c.getDouble("lobby.x", 0.5),
                c.getDouble("lobby.y", 100.0),
                c.getDouble("lobby.z", 0.5),
                (float) c.getDouble("lobby.yaw", 0.0),
                (float) c.getDouble("lobby.pitch", 0.0)
        );
    }

    private void setupEconomy() {
        boolean enabledInConfig = getConfig().getBoolean("economy.enabled", false);
        if (!enabledInConfig) { this.economy = null; return; }
        try {
            Class<?> ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object services = getServer().getServicesManager();
            java.lang.reflect.Method getRegistration = services.getClass().getMethod("getRegistration", Class.class);
            Object rsp = getRegistration.invoke(services, ecoClass);
            if (rsp != null) {
                java.lang.reflect.Method getProvider = rsp.getClass().getMethod("getProvider");
                this.economy = getProvider.invoke(rsp);
            }
            if (this.economy == null) getLogger().warning("Vault economy not found; economy features disabled");
        } catch (ClassNotFoundException e) {
            this.economy = null;
            getLogger().warning("Vault not installed; economy disabled");
        } catch (Throwable t) {
            this.economy = null;
            getLogger().warning("Failed to hook Vault economy: " + t.getMessage());
        }
    }
}