package org.jlakctep.santaPillars.game;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jlakctep.santaPillars.SantaPillars;

import java.util.*;

public class ArenaManager {
    private final SantaPillars plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, String> playerArena = new HashMap<>();

    // Режим редактирования: игрок -> id арены
    private final Map<UUID, String> editing = new HashMap<>();
    // Глобальные наблюдатели (в лобби)
    private final java.util.Set<UUID> globalSpectators = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Привязка наблюдателя к арене, за которой он наблюдает
    private final Map<UUID, String> spectatorArena = new java.util.concurrent.ConcurrentHashMap<>();

    public ArenaManager(SantaPillars plugin) { this.plugin = plugin; }

    public void load(YamlConfiguration cfg) {
        arenas.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("arenas");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            Arena a = Arena.load(plugin, id, sec.getConfigurationSection(id));
            if (a != null) arenas.put(id, a);
        }
    }

    public void save(YamlConfiguration cfg) {
        cfg.set("arenas", null);
        ConfigurationSection sec = cfg.createSection("arenas");
        for (Map.Entry<String, Arena> e : arenas.entrySet()) {
            e.getValue().save(sec.createSection(e.getKey()));
        }
    }

    public Arena create(String id, GameSize size, World world) {
        Arena a = new Arena(plugin, id, size, world);
        arenas.put(id, a);
        return a;
    }

    public Arena get(String id) { return arenas.get(id); }

    public Arena byPlayer(UUID u) {
        String id = playerArena.get(u);
        return id == null ? null : arenas.get(id);
    }

    public void bindPlayer(UUID u, String id) { playerArena.put(u, id); }
    public void unbindPlayer(UUID u) { playerArena.remove(u); }

    public Arena findJoinable(GameSize size) {
        for (Arena a : arenas.values()) {
            if (a.size == size && a.isJoinable()) return a;
        }
        return null;
    }

    public Arena findAnyJoinable() {
        for (Arena a : arenas.values()) if (a.isJoinable()) return a;
        return null;
    }

    public int countPlayersInMode(GameSize size) {
        int sum = 0;
        for (Arena a : arenas.values()) if (a.size == size) sum += a.playersCount();
        return sum;
    }

    public void shutdown() { for (Arena a : arenas.values()) a.shutdown(); }

    public Collection<Arena> all() { return arenas.values(); }

    public boolean inAny(UUID u) { return playerArena.containsKey(u); }

    // Редактирование
    public void beginEdit(UUID player, String id) { editing.put(player, id); }
    public String editingOf(UUID player) { return editing.get(player); }
    public void endEdit(UUID player) { editing.remove(player); }

    // Глобальный spectate (в лобби)
    public void enterGlobalSpectate(UUID u) { globalSpectators.add(u); }
    public void leaveGlobalSpectate(UUID u) { globalSpectators.remove(u); }
    public boolean isGlobalSpectator(UUID u) { return globalSpectators.contains(u); }

    // Привязка наблюдения к арене
    public void startSpectating(UUID u, String arenaId) { spectatorArena.put(u, arenaId); enterGlobalSpectate(u); }
    public void stopSpectating(UUID u) { spectatorArena.remove(u); leaveGlobalSpectate(u); }
    public String spectatingArenaOf(UUID u) { return spectatorArena.get(u); }
    public java.util.List<UUID> spectatorsOfArena(String arenaId) {
        java.util.ArrayList<UUID> list = new java.util.ArrayList<>();
        for (var e : spectatorArena.entrySet()) if (arenaId.equals(e.getValue())) list.add(e.getKey());
        return list;
    }

    public boolean removeArena(String id) {
        Arena a = arenas.remove(id);
        return a != null;
    }
}