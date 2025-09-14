package org.jlakctep.santaPillars;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import org.jlakctep.santaPillars.command.PillarsCommand;
import org.jlakctep.santaPillars.game.ArenaManager;
import org.jlakctep.santaPillars.item.ItemPool;
import org.jlakctep.santaPillars.listener.GameListener;
import org.jlakctep.santaPillars.npc.NPCManager;
import org.jlakctep.santaPillars.util.Msg;
import org.jlakctep.santaPillars.stats.StatsManager;
import org.jlakctep.santaPillars.papi.PillarsExpansion;
import org.jlakctep.santaPillars.papi.SimpleTokenExpansion;

import java.io.File;
 

public final class SantaPillars extends JavaPlugin {

    private ArenaManager arenaManager;
    private NPCManager npcManager;
    private ItemPool itemPool;
    private Object economy; // Vault Economy provider via reflection to avoid hard dependency
    private StatsManager statsManager;

    private File arenasFile, npcsFile;
    private YamlConfiguration arenasCfg, npcsCfg;

    private File worldsDir;

    @Override
    public void onEnable() {
        // Ресурсы
        saveDefaultConfig();
        // Копируем дефолтные файлы только если отсутствуют (чтобы не спамить WARNING)
        try {
            java.io.File data = getDataFolder();
            if (!new java.io.File(data, "messages.yml").exists()) saveResource("messages.yml", false);
            if (!new java.io.File(data, "arenas.yml").exists()) saveResource("arenas.yml", false);
            if (!new java.io.File(data, "npcs.yml").exists()) saveResource("npcs.yml", false);
        } catch (Throwable ignored) {}
        

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
        statsManager = new StatsManager(this);
        statsManager.load();

        // Аггрегирующие права (pillars.default/donate/admin)
        try { registerAggregatedPermissions(); } catch (Throwable ignored) {}

        arenaManager = new ArenaManager(this);
        arenaManager.load(arenasCfg);

        npcManager = new NPCManager(this);
        npcManager.loadAndSpawn(npcsCfg);

        // Регистрация команд (Paper API)
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
        Command join = new Command("join") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command leave = new Command("leave") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command lobby = new Command("lobby") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command setlobby = new Command("setlobby") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command forcestart = new Command("forcestart") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command arena = new Command("arena") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command floor = new Command("floor") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command team = new Command("team") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command editleave = new Command("editleave") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command arenaremove = new Command("arenaremove") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command npc = new Command("npc") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Command spectate = new Command("spectate") { @Override public boolean execute(CommandSender s, String l, String[] a) { return delegate.onCommand(s, this, l, a); } @Override public java.util.List<String> tabComplete(CommandSender s, String a, String[] ar){ return delegate.onTabComplete(s, this, a, ar);} };
        Bukkit.getCommandMap().register("santapillars", pillars);
        Bukkit.getCommandMap().register("santapillars", join);
        Bukkit.getCommandMap().register("santapillars", leave);
        Bukkit.getCommandMap().register("santapillars", lobby);
        Bukkit.getCommandMap().register("santapillars", setlobby);
        Bukkit.getCommandMap().register("santapillars", forcestart);
        Bukkit.getCommandMap().register("santapillars", arena);
        Bukkit.getCommandMap().register("santapillars", floor);
        Bukkit.getCommandMap().register("santapillars", team);
        Bukkit.getCommandMap().register("santapillars", editleave);
        Bukkit.getCommandMap().register("santapillars", arenaremove);
        Bukkit.getCommandMap().register("santapillars", npc);
        Bukkit.getCommandMap().register("santapillars", spectate);

        // Листенеры
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        // Листенер для позднего хука экономики (если Vault/провайдер загружаются позже)
        Bukkit.getPluginManager().registerEvents(new EconomyHookListener(), this);

        // Экономика (Vault)
        setupEconomy();
        // Повторная попытка через 2 секунды, если провайдера пока нет
        getServer().getScheduler().runTaskLater(this, () -> { if (this.economy == null) setupEconomy(); }, 40L);

        // PlaceholderAPI (если установлен): регистрируем расширение
        try {
            if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                try {
                    ClassLoader cl = this.getClass().getClassLoader();
                    Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", false, cl);
                    boolean ok = new PillarsExpansion(this).register();
                    getLogger().info("[PAPI] PillarsExpansion register() => " + ok);
                } catch (ClassNotFoundException | NoClassDefFoundError cnfe) {
                    getLogger().warning("[PAPI] PlaceholderAPI class not visible to plugin classloader: " + cnfe);
                }
            } else {
                getLogger().info("[PAPI] PlaceholderAPI not found, skipping expansion registration");
            }
        } catch (Throwable t) {
            getLogger().warning("[PAPI] Failed to register PillarsExpansion: " + t);
        }

        getLogger().info("SantaPillars enabled (Paper)");
    }

    @Override
    public void onDisable() {
        if (arenaManager != null) arenaManager.shutdown();
        if (npcManager != null) npcManager.despawnAll();
        if (statsManager != null) statsManager.save();
        saveArenas();
        saveNPCs();
        getLogger().info("SantaPillars disabled");
    }

    public ArenaManager arenas() { return arenaManager; }
    public NPCManager npcs() { return npcManager; }
    public ItemPool items() { return itemPool; }
    public StatsManager stats() { return statsManager; }

    private String formatNumber(double v) {
        if (Math.floor(v) == v) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private void registerAggregatedPermissions() {
        java.util.Map<String, java.util.List<String>> packs = new java.util.HashMap<>();
        packs.put("default", getConfig().getStringList("permission_sets.default"));
        packs.put("donate", getConfig().getStringList("permission_sets.donate"));
        packs.put("admin", getConfig().getStringList("permission_sets.admin"));
        for (var e : packs.entrySet()) {
            String node = "pillars." + e.getKey();
            java.util.Map<String, Boolean> children = new java.util.HashMap<>();
            for (String ch : e.getValue()) children.put(ch, true);
            Permission existing = getServer().getPluginManager().getPermission(node);
            if (existing != null) {
                existing.getChildren().clear();
                existing.getChildren().putAll(children);
                existing.recalculatePermissibles();
            } else {
                Permission p = new Permission(node, PermissionDefault.FALSE, children);
                getServer().getPluginManager().addPermission(p);
            }
        }
    }
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

    public double getPlayerBalance(org.bukkit.OfflinePlayer player) {
        try {
            java.lang.reflect.Field ecoF = SantaPillars.class.getDeclaredField("economy");
            ecoF.setAccessible(true);
            Object eco = ecoF.get(this);
            if (eco == null || player == null) return 0.0;
            try {
                java.lang.reflect.Method m = eco.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                Object res = m.invoke(eco, player);
                if (res instanceof Number n) return n.doubleValue();
            } catch (NoSuchMethodException ignored) {}
            try {
                java.lang.reflect.Method m2 = eco.getClass().getMethod("getBalance", String.class);
                Object res = m2.invoke(eco, player.getName());
                if (res instanceof Number n) return n.doubleValue();
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        return 0.0;
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
        Location loc = new Location(
                w,
                c.getDouble("lobby.x", 0.5),
                c.getDouble("lobby.y", 100.0),
                c.getDouble("lobby.z", 0.5),
                (float) c.getDouble("lobby.yaw", 0.0),
                (float) c.getDouble("lobby.pitch", 0.0)
        );
        try {
            // Если под точкой лобби нет блока (воздух/войд) — ставим на самый высокий блок по XZ
            int feetY = (int) Math.floor(loc.getY()) - 1;
            org.bukkit.block.Block below = w.getBlockAt(loc.getBlockX(), feetY, loc.getBlockZ());
            if (isAirLike(below.getType())) {
                int hy = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
                loc.setY(hy + 1.0);
                below = w.getBlockAt(loc.getBlockX(), hy, loc.getBlockZ());
            }
            // В полностью пустом столбце (void) создадим безопасный блок под ногами
            if (isAirLike(below.getType())) {
                below.setType(org.bukkit.Material.BARRIER, false);
                loc.setY(below.getY() + 1.0);
            }
        } catch (Throwable ignored) {}
        return loc;
    }

    private static boolean isAirLike(org.bukkit.Material mt) {
        return mt == org.bukkit.Material.AIR || mt == org.bukkit.Material.CAVE_AIR || mt == org.bukkit.Material.VOID_AIR;
    }

    public void setupEconomy() {
        boolean enabledInConfig = getConfig().getBoolean("economy.enabled", false);
        if (!enabledInConfig) { this.economy = null; return; }
        try {
            // Попытка 1: загрузить Economy обычным путём
            Class<?> ecoClass;
            try {
                ecoClass = Class.forName("net.milkbowl.vault.economy.Economy");
            } catch (ClassNotFoundException ex) {
                // Попытка 2: через classloader плагина Vault (если есть)
                var vaultPlugin = getServer().getPluginManager().getPlugin("Vault");
                if (vaultPlugin != null) {
                    try {
                        ecoClass = Class.forName("net.milkbowl.vault.economy.Economy", true, vaultPlugin.getClass().getClassLoader());
                    } catch (ClassNotFoundException ex2) {
                        throw ex2;
                    }
                } else {
                    throw ex;
                }
            }

            Object services = getServer().getServicesManager();
            java.lang.reflect.Method getRegistration = services.getClass().getMethod("getRegistration", Class.class);
            Object rsp = getRegistration.invoke(services, ecoClass);
            if (rsp != null) {
                java.lang.reflect.Method getProvider = rsp.getClass().getMethod("getProvider");
                this.economy = getProvider.invoke(rsp);
            }
            if (this.economy == null) getLogger().warning("Vault economy provider not found; economy features disabled");
        } catch (ClassNotFoundException e) {
            this.economy = null;
            getLogger().warning("Vault plugin or API not available; economy disabled");
        } catch (Throwable t) {
            this.economy = null;
            getLogger().warning("Failed to hook Vault economy: " + t.getMessage());
        }
    }
}

class EconomyHookListener implements Listener {
    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (!(org.bukkit.Bukkit.getPluginManager().getPlugin("SantaPillars") instanceof SantaPillars pl)) return;
        try {
            java.lang.reflect.Field f = SantaPillars.class.getDeclaredField("economy");
            f.setAccessible(true);
            if (f.get(pl) == null) {
                org.bukkit.Bukkit.getScheduler().runTask(pl, () -> pl.setupEconomy());
            }
        } catch (Throwable ignored) {}
    }
}