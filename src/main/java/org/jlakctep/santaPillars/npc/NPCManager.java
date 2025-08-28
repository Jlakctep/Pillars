package org.jlakctep.santaPillars.npc;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.jlakctep.santaPillars.SantaPillars;
import org.jlakctep.santaPillars.game.GameSize;

import java.util.*;

public class NPCManager {
    private final SantaPillars plugin;
    private final Map<String, NPCMeta> npcs = new LinkedHashMap<>();

    public NPCManager(SantaPillars plugin) { this.plugin = plugin; }

    public void loadAndSpawn(YamlConfiguration cfg) {
        npcs.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("npcs");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            var s = sec.getConfigurationSection(id);
            GameSize size = GameSize.fromKey(s.getString("size", "1x1"));
            World w = Bukkit.getWorld(s.getString("world", "world"));
            if (size == null || w == null) continue;
            double x = s.getDouble("x"), y = s.getDouble("y"), z = s.getDouble("z");
            float yaw = (float)s.getDouble("yaw"), pitch = (float)s.getDouble("pitch");
            EntityType type = EntityType.valueOf(s.getString("type", "VILLAGER"));
            String name = s.getString("name", ChatColor.GOLD + "Pillars " + size.key() + ChatColor.YELLOW + " | ПКМ");
            NPCMeta meta = new NPCMeta(id, size, new Location(w, x, y, z, yaw, pitch), type, name);
            npcs.put(id, meta);
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnAll, 20L);
    }

    public void save(YamlConfiguration cfg) {
        cfg.set("npcs", null);
        ConfigurationSection sec = cfg.createSection("npcs");
        for (var e : npcs.entrySet()) {
            var id = e.getKey(); var meta = e.getValue();
            ConfigurationSection s = sec.createSection(id);
            s.set("size", meta.size.key());
            s.set("world", meta.loc.getWorld().getName());
            s.set("x", meta.loc.getX()); s.set("y", meta.loc.getY()); s.set("z", meta.loc.getZ());
            s.set("yaw", (double) meta.loc.getYaw()); s.set("pitch", (double) meta.loc.getPitch());
            s.set("type", meta.type.name());
            s.set("name", meta.name);
        }
    }

    public void spawnAll() {
        despawnAll();
        for (NPCMeta meta : npcs.values()) {
            LivingEntity e = (LivingEntity) meta.loc.getWorld().spawnEntity(meta.loc, meta.type);
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setCanPickupItems(false);
            try { e.setRotation(meta.loc.getYaw(), meta.loc.getPitch()); } catch (Throwable ignored) {}
            // Авто-имя по режиму и онлайнам
            int total = plugin.arenas().countPlayersInMode(meta.size);
            String header;
            if (meta.size == org.jlakctep.santaPillars.game.GameSize.S2) header = ChatColor.YELLOW + "§lСоло";
            else if (meta.size == org.jlakctep.santaPillars.game.GameSize.S4) header = ChatColor.YELLOW + "§l1x4";
            else header = ChatColor.YELLOW + "§l1x8";
            String footer = ChatColor.GRAY + String.valueOf(total) + " игроков";
            e.setCustomName(header + "\n" + footer);
            e.setCustomNameVisible(true);
            NamespacedKey keyMode = new NamespacedKey(plugin, "mode");
            e.getPersistentDataContainer().set(keyMode, PersistentDataType.STRING, meta.size.key());
            meta.entity = e;
        }
    }

    public void despawnAll() {
        for (NPCMeta meta : npcs.values()) {
            if (meta.entity != null && meta.entity.isValid()) meta.entity.remove();
            meta.entity = null;
        }
    }

    public boolean createNPC(String id, GameSize size, Location loc, EntityType type) {
        if (npcs.containsKey(id)) return false;
        String name = ChatColor.GOLD + "Pillars " + size.key() + ChatColor.YELLOW + " | ПКМ";
        NPCMeta meta = new NPCMeta(id, size, loc, type, name);
        npcs.put(id, meta);
        spawnAll();
        return true;
    }

    public boolean removeNPC(String id) {
        NPCMeta meta = npcs.remove(id);
        if (meta == null) return false;
        if (meta.entity != null) meta.entity.remove();
        return true;
    }

    public Optional<GameSize> readSizeFrom(LivingEntity e) {
        NamespacedKey keyMode = new NamespacedKey(plugin, "mode");
        String s = e.getPersistentDataContainer().get(keyMode, PersistentDataType.STRING);
        return Optional.ofNullable(GameSize.fromKey(s == null ? "" : s));
    }

    private static class NPCMeta {
        final String id; final GameSize size; final Location loc; final EntityType type; final String name;
        LivingEntity entity;
        NPCMeta(String id, GameSize size, Location loc, EntityType type, String name) { this.id = id; this.size = size; this.loc = loc; this.type = type; this.name = name; }
    }
}